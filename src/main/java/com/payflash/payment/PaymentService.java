package com.payflash.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflash.common.BusinessException;
import com.payflash.payment.PaymentDtos.TopUpOrderResponse;
import com.payflash.payment.PaymentDtos.VerifyPaymentRequest;
import com.payflash.wallet.WalletService;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Razorpay top-up flow:
 * <ol>
 *   <li>Client asks for an order ({@link #createTopUpOrder}); we create it at Razorpay and store it as CREATED.</li>
 *   <li>Client pays in Razorpay Checkout.</li>
 *   <li>The wallet is credited by whichever arrives first: the client's {@link #verifyCheckout} call or
 *       Razorpay's {@link #handleWebhook}. The webhook is the source of truth: it still arrives if the
 *       user closes the tab right after paying.</li>
 * </ol>
 * Exactly-once credit is enforced twice: the order row is locked and checked for PAID, and the
 * wallet top-up uses the idempotency key "rzp:&lt;order id&gt;".
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String CURRENCY = "INR";

    private final PaymentGateway gateway;
    private final PaymentOrderRepository orders;
    private final WalletService wallets;
    private final RazorpayProperties props;
    private final ObjectMapper mapper;

    public PaymentService(PaymentGateway gateway, PaymentOrderRepository orders, WalletService wallets,
                          RazorpayProperties props, ObjectMapper mapper) {
        this.gateway = gateway;
        this.orders = orders;
        this.wallets = wallets;
        this.props = props;
        this.mapper = mapper;
    }

    /** Idempotent: retrying with the same key returns the same order instead of creating another one. */
    public TopUpOrderResponse createTopUpOrder(Long userId, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw BusinessException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key header must be 1-100 characters");
        }
        var existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            if (existing.get().getAmount().compareTo(amount) != 0) {
                throw BusinessException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used for a different request");
            }
            return TopUpOrderResponse.from(existing.get(), props.keyId());
        }
        // The external call is deliberately outside any DB transaction: holding a connection open while
        // waiting up to 10s on Razorpay would drain the pool under load.
        String receipt = "pf_" + userId + "_" + UUID.randomUUID().toString().substring(0, 8);
        var gatewayOrder = gateway.createOrder(toMinor(amount), CURRENCY, receipt);
        PaymentOrder order = orders.save(new PaymentOrder(userId, gatewayOrder.id(), amount, CURRENCY, idempotencyKey));
        log.info("Created Razorpay order {} for user {} ({} {})", order.getProviderOrderId(), userId, amount, CURRENCY);
        return TopUpOrderResponse.from(order, props.keyId());
    }

    @Transactional(readOnly = true)
    public TopUpOrderResponse getOrder(Long userId, String orderId) {
        return orders.findByProviderOrderId(orderId)
                .filter(o -> o.getUserId().equals(userId))
                .map(o -> TopUpOrderResponse.from(o, props.keyId()))
                .orElseThrow(() -> BusinessException.notFound("Payment order not found"));
    }

    /** Called by the browser with the values Checkout handed to its success callback. */
    @Transactional
    public TopUpOrderResponse verifyCheckout(Long userId, VerifyPaymentRequest req) {
        requireApiKeys();
        if (!RazorpaySignatures.isValidPayment(req.razorpayOrderId(), req.razorpayPaymentId(),
                req.razorpaySignature(), props.keySecret())) {
            throw BusinessException.badRequest("INVALID_SIGNATURE", "Payment signature verification failed");
        }
        PaymentOrder order = orders.lockByProviderOrderId(req.razorpayOrderId())
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> BusinessException.notFound("Payment order not found"));
        creditOnce(order, req.razorpayPaymentId());
        return TopUpOrderResponse.from(order, props.keyId());
    }

    /**
     * Razorpay webhook. Returns normally for events we accept or deliberately ignore (Razorpay then
     * stops retrying); throws only for a bad signature.
     */
    @Transactional
    public void handleWebhook(byte[] rawBody, String signature) {
        if (!props.webhookConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_NOT_CONFIGURED",
                    "Razorpay webhook secret is not configured on the server");
        }
        if (!RazorpaySignatures.isValidWebhook(rawBody, signature, props.webhookSecret())) {
            throw BusinessException.badRequest("INVALID_SIGNATURE", "Webhook signature verification failed");
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (IOException e) {
            throw BusinessException.badRequest("INVALID_PAYLOAD", "Webhook body is not valid JSON");
        }
        String event = root.path("event").asText();
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String orderId = payment.path("order_id").asText(null);
        String paymentId = payment.path("id").asText(null);
        if (orderId == null || paymentId == null) {
            log.info("Ignoring Razorpay webhook '{}' without a payment entity", event);
            return;
        }
        var maybeOrder = orders.lockByProviderOrderId(orderId);
        if (maybeOrder.isEmpty()) {
            log.warn("Razorpay webhook '{}' for unknown order {}; ignoring", event, orderId);
            return;
        }
        PaymentOrder order = maybeOrder.get();
        switch (event) {
            case "payment.captured", "order.paid" -> {
                long paidMinor = payment.path("amount").asLong(-1);
                String currency = payment.path("currency").asText("");
                if (paidMinor != toMinor(order.getAmount()) || !order.getCurrency().equals(currency)) {
                    // Never credit an amount other than the one we ordered.
                    log.error("Amount mismatch on order {}: expected {} {} paise, webhook says {} {}",
                            orderId, toMinor(order.getAmount()), order.getCurrency(), paidMinor, currency);
                    return;
                }
                creditOnce(order, paymentId);
            }
            case "payment.failed" -> {
                order.markFailed(paymentId);
                log.info("Payment {} failed for order {}", paymentId, orderId);
            }
            default -> log.debug("Ignoring Razorpay webhook event '{}'", event);
        }
    }

    /** Caller must hold the order row lock. */
    private void creditOnce(PaymentOrder order, String paymentId) {
        if (order.getStatus() == PaymentOrder.Status.PAID) {
            return; // the other path (verify or webhook) already credited it
        }
        var result = wallets.topUp(order.getUserId(), order.getAmount(), "rzp:" + order.getProviderOrderId(),
                "Razorpay top-up " + paymentId);
        order.markPaid(paymentId, result.transaction().reference());
        log.info("Order {} paid by {}; wallet of user {} credited {}", order.getProviderOrderId(), paymentId,
                order.getUserId(), order.getAmount());
    }

    private void requireApiKeys() {
        if (!props.apiConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_NOT_CONFIGURED",
                    "Razorpay keys are not configured on the server");
        }
    }

    /** Rupees to paise. Amounts are validated to 2 decimal places, so this is exact. */
    static long toMinor(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }
}
