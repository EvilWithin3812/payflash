package com.payflash.sale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflash.common.BusinessException;
import com.payflash.outbox.Topics;
import com.payflash.sale.SaleDtos.CreateSaleRequest;
import com.payflash.sale.SaleDtos.OrderState;
import com.payflash.sale.SaleDtos.SaleOrderResponse;
import com.payflash.sale.SaleDtos.SaleResponse;
import com.payflash.wallet.WalletService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Flash sale: thousands of buyers, a few hundred units, zero overselling.
 *
 * <pre>
 * reserve  ──► Redis Lua (atomic: one unit per user, pop a free unit) ──► Kafka sale.orders ──► 202 PROCESSING
 *                                                                              │
 * SaleOrderListener ◄──────────────────────────────────────────────────────────┘ writes sale_orders(HELD)
 * pay      ──► lock order row ──► wallet purchase (debit) ──► PAID
 * sweeper  ──► HELD and past expires_at ──► EXPIRED ──► unit back to the Redis pool
 * </pre>
 */
@Service
public class SaleService {

    private static final Logger log = LoggerFactory.getLogger(SaleService.class);
    /** The hold lookup key outlives the hold, so a slow queue can't turn a valid hold into "not found". */
    private static final Duration HOLD_LOOKUP_GRACE = Duration.ofMinutes(5);
    private static final int SWEEP_BATCH = 500;

    private final SaleRepository sales;
    private final SaleOrderRepository orders;
    private final SaleCatalog catalog;
    private final SaleInventory inventory;
    private final WalletService wallets;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public SaleService(SaleRepository sales, SaleOrderRepository orders, SaleCatalog catalog, SaleInventory inventory,
                       WalletService wallets, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                       MeterRegistry metrics) {
        this.sales = sales;
        this.orders = orders;
        this.catalog = catalog;
        this.inventory = inventory;
        this.wallets = wallets;
        this.kafka = kafka;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ catalogue

    @Transactional
    public SaleResponse create(CreateSaleRequest req) {
        if (!req.endsAt().isAfter(req.startsAt())) {
            throw BusinessException.badRequest("INVALID_SALE_WINDOW", "endsAt must be after startsAt");
        }
        int hold = req.holdSeconds() == null ? 300 : req.holdSeconds();
        Sale sale = sales.save(new Sale(req.name().trim(), req.description(), req.price(), req.totalUnits(),
                req.startsAt(), req.endsAt(), hold));
        afterCommit(() -> {
            inventory.rebuild(sale.getId(), sale.getTotalUnits(), List.of(), List.of());
            catalog.put(sale);
        });
        log.info("Created sale {} '{}' with {} units at {}", sale.getId(), sale.getName(), sale.getTotalUnits(),
                sale.getPrice());
        return SaleResponse.from(sale, sale.getTotalUnits(), Instant.now());
    }

    public List<SaleResponse> listUpcomingAndLive() {
        Instant now = Instant.now();
        return catalog.notEnded().stream().map(s -> SaleResponse.from(s, inventory.remaining(s.getId()), now)).toList();
    }

    public SaleResponse get(long saleId) {
        Sale sale = catalog.get(saleId);
        return SaleResponse.from(sale, inventory.remaining(saleId), Instant.now());
    }

    /**
     * Rebuilds a sale's Redis pool from the database (e.g. after Redis lost its data).
     * Reservations still in the Kafka queue are not in the database yet, so only run it while the queue is idle.
     */
    @Transactional(readOnly = true)
    public SaleResponse rebuildInventory(long saleId) {
        Sale sale = catalog.get(saleId);
        List<SaleOrder> live = orders.findLiveOrders(saleId);
        inventory.rebuild(saleId, sale.getTotalUnits(), live.stream().map(SaleOrder::getUnitNo).toList(),
                live.stream().map(SaleOrder::getUserId).toList());
        log.warn("Rebuilt inventory of sale {}: {} units taken", saleId, live.size());
        return SaleResponse.from(sale, inventory.remaining(saleId), Instant.now());
    }

    // ------------------------------------------------------------------ hot path

    /**
     * No database access on this path: catalog (memory) → Redis Lua → Kafka. That is what lets it absorb a
     * burst; Postgres sees the orders at the consumer's steady pace instead.
     */
    public SaleOrderResponse reserve(long userId, long saleId) {
        Sale sale = catalog.get(saleId);
        Instant now = Instant.now();
        switch (sale.phaseAt(now)) {
            case UPCOMING -> throw outcome("not_started", BusinessException.conflict("SALE_NOT_STARTED",
                    "This sale has not started yet"));
            case ENDED -> throw outcome("ended", BusinessException.conflict("SALE_ENDED", "This sale has ended"));
            case LIVE -> { /* go on */ }
        }
        long unit = inventory.reserve(saleId, userId);
        if (unit == SaleInventory.SOLD_OUT) {
            throw outcome("sold_out", BusinessException.conflict("SOLD_OUT", "Sorry, this sale is sold out"));
        }
        if (unit == SaleInventory.ALREADY_RESERVED) {
            throw outcome("duplicate", BusinessException.conflict("ALREADY_RESERVED",
                    "You already reserved in this sale. Each person gets one hold per sale, even if it expired"));
        }

        UUID holdToken = UUID.randomUUID();
        Instant expiresAt = now.plus(sale.holdDuration());
        inventory.rememberHold(holdToken, saleId, userId, (int) unit, sale.holdDuration().plus(HOLD_LOOKUP_GRACE));
        var event = new SaleEvents.OrderRequested(holdToken, saleId, userId, (int) unit, sale.getPrice(), expiresAt);
        try {
            kafka.send(Topics.SALE_ORDERS, saleId + ":" + userId, mapper.writeValueAsString(event))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Couldn't queue the order: give the unit back so it isn't lost, and let the client retry.
            inventory.release(saleId, userId, (int) unit);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Could not queue reservation {} for sale {}; unit {} released", holdToken, saleId, unit, e);
            throw outcome("queue_error", new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_UNAVAILABLE",
                    "Could not process your reservation, please retry"));
        }
        metrics.counter("payflash.sale.reservations", "outcome", "reserved").increment();
        return new SaleOrderResponse(holdToken, saleId, (int) unit, OrderState.PROCESSING, sale.getPrice(), expiresAt,
                null, now);
    }

    // ------------------------------------------------------------------ order processing (Kafka consumer)

    /** Idempotent: a redelivered message finds its row by hold token and does nothing. */
    @Transactional
    public void recordHold(SaleEvents.OrderRequested e) {
        if (orders.existsByHoldToken(e.holdToken())) {
            return;
        }
        boolean expired = !Instant.now().isBefore(e.expiresAt());
        SaleOrder order = orders.save(new SaleOrder(e.saleId(), e.userId(), e.unitNo(), e.holdToken(), e.price(),
                e.expiresAt(), expired ? SaleOrder.Status.EXPIRED : SaleOrder.Status.HELD));
        if (expired) {
            // The queue was slower than the hold: the buyer never had a chance to pay. Free the unit.
            log.warn("Reservation {} reached the database after its hold expired", e.holdToken());
            afterCommit(() -> inventory.release(order.getSaleId(), order.getUserId(), order.getUnitNo()));
        }
    }

    // ------------------------------------------------------------------ buyer actions

    @Transactional(readOnly = true)
    public SaleOrderResponse getOrder(long userId, UUID holdToken) {
        var order = orders.findByHoldToken(holdToken).filter(o -> o.getUserId() == userId);
        if (order.isPresent()) {
            return SaleOrderResponse.from(order.get(), Instant.now());
        }
        return inventory.pendingHold(holdToken)
                .filter(h -> h.userId() == userId)
                .map(h -> {
                    Sale sale = catalog.get(h.saleId());
                    return new SaleOrderResponse(holdToken, h.saleId(), h.unitNo(), OrderState.PROCESSING,
                            sale.getPrice(), null, null, null);
                })
                .orElseThrow(() -> BusinessException.notFound("Reservation not found"));
    }

    @Transactional(readOnly = true)
    public List<SaleOrderResponse> myOrders(long userId) {
        Instant now = Instant.now();
        return orders.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).stream()
                .map(o -> SaleOrderResponse.from(o, now)).toList();
    }

    /**
     * Pays a hold from the wallet. Locks the order row, so it can't race the expiry sweeper (which skips
     * locked rows) or a double-clicked pay button. Retrying after success returns the paid order.
     */
    @Transactional
    public SaleOrderResponse pay(long userId, UUID holdToken) {
        SaleOrder order = orders.lockByHoldToken(holdToken).filter(o -> o.getUserId() == userId).orElse(null);
        if (order == null) {
            boolean queued = inventory.pendingHold(holdToken).filter(h -> h.userId() == userId).isPresent();
            throw queued
                    ? BusinessException.conflict("ORDER_PROCESSING", "Your reservation is still being processed, retry in a moment")
                    : BusinessException.notFound("Reservation not found");
        }
        Instant now = Instant.now();
        if (order.getStatus() == SaleOrder.Status.PAID) {
            return SaleOrderResponse.from(order, now);
        }
        if (order.isExpiredAt(now)) {
            throw new BusinessException(HttpStatus.GONE, "HOLD_EXPIRED", "Your reservation expired before payment");
        }
        Sale sale = catalog.get(order.getSaleId());
        var payment = wallets.purchase(userId, order.getPrice(), "sale:" + holdToken,
                "Flash sale: " + sale.getName() + " #" + order.getUnitNo());
        order.markPaid(payment.transaction().reference());
        metrics.counter("payflash.sale.payments").increment();
        return SaleOrderResponse.from(order, now);
    }

    // ------------------------------------------------------------------ expiry

    /** @return number of holds expired in this batch */
    @Transactional
    public int expireHolds() {
        List<SaleOrder> expired = orders.claimExpiredHolds(SWEEP_BATCH);
        for (SaleOrder o : expired) {
            o.markExpired();
        }
        if (!expired.isEmpty()) {
            // Only after the commit: if the transaction rolled back, the units must stay taken.
            afterCommit(() -> expired.forEach(o -> inventory.expire(o.getSaleId(), o.getUserId(), o.getUnitNo())));
            metrics.counter("payflash.sale.expired_holds").increment(expired.size());
            log.info("Expired {} unpaid holds", expired.size());
        }
        return expired.size();
    }

    // ------------------------------------------------------------------ helpers

    private BusinessException outcome(String outcome, BusinessException e) {
        metrics.counter("payflash.sale.reservations", "outcome", outcome).increment();
        return e;
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
