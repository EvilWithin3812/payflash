package com.payflash.sale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflash.outbox.Topics;
import com.payflash.sale.SaleDtos.StockUpdate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Everything in the sale module that runs without a user request. */
@Configuration
public class SaleBackgroundJobs {

    /** Turns queued reservations into order rows. Several partitions + concurrency = parallel, ordered per key. */
    @Component
    @ConditionalOnProperty(name = "app.sales.order-processor-enabled", havingValue = "true", matchIfMissing = true)
    static class SaleOrderListener {

        private final SaleService sales;
        private final ObjectMapper mapper;

        SaleOrderListener(SaleService sales, ObjectMapper mapper) {
            this.sales = sales;
            this.mapper = mapper;
        }

        @KafkaListener(topics = Topics.SALE_ORDERS, groupId = "${app.sales.consumer-group:sale-order-processor}",
                concurrency = "3")
        void onOrderRequested(ConsumerRecord<String, String> record) throws JsonProcessingException {
            sales.recordHold(mapper.readValue(record.value(), SaleEvents.OrderRequested.class));
        }
    }

    /** Expires unpaid holds and returns their units to the pool. */
    @Component
    @ConditionalOnProperty(name = "app.sales.expiry-sweeper-enabled", havingValue = "true", matchIfMissing = true)
    static class HoldExpirySweeper {

        private final SaleService sales;

        HoldExpirySweeper(SaleService sales) {
            this.sales = sales;
        }

        @Scheduled(fixedDelayString = "${app.sales.sweep-interval-ms:2000}")
        void sweep() {
            // Drain in batches so a big backlog (e.g. after downtime) clears in one run.
            while (sales.expireHolds() > 0) {
                Thread.onSpinWait();
            }
        }
    }

    /**
     * Pushes "units left" to WebSocket subscribers of /topic/sales/{id}. Polling Redis twice a second
     * instead of broadcasting on every reservation caps the fan-out: a burst of 5,000 reservations becomes
     * at most 2 messages per second per sale, and every instance reads the same shared Redis count.
     */
    @Component
    @ConditionalOnProperty(name = "app.sales.stock-broadcast-enabled", havingValue = "true", matchIfMissing = true)
    static class StockBroadcaster {

        private final SaleCatalog catalog;
        private final SaleInventory inventory;
        private final SimpMessagingTemplate ws;
        private final Map<Long, Long> lastSent = new ConcurrentHashMap<>();

        StockBroadcaster(SaleCatalog catalog, SaleInventory inventory, SimpMessagingTemplate ws) {
            this.catalog = catalog;
            this.inventory = inventory;
            this.ws = ws;
        }

        @Scheduled(fixedDelayString = "${app.sales.broadcast-interval-ms:500}")
        void broadcast() {
            List<Sale> sales = catalog.notEnded();
            for (Sale sale : sales) {
                long remaining = inventory.remaining(sale.getId());
                Long previous = lastSent.put(sale.getId(), remaining);
                if (previous == null || previous != remaining) {
                    ws.convertAndSend("/topic/sales/" + sale.getId(),
                            new StockUpdate(sale.getId(), remaining, sale.getTotalUnits()));
                }
            }
        }
    }

    /** If Redis lost a sale's pool (restart without persistence), rebuild it from Postgres on startup. */
    @Bean
    InventoryWarmup inventoryWarmup(SaleCatalog catalog, SaleInventory inventory, SaleService sales) {
        return new InventoryWarmup(catalog, inventory, sales);
    }

    static class InventoryWarmup {

        private static final Logger log = LoggerFactory.getLogger(InventoryWarmup.class);

        private final SaleCatalog catalog;
        private final SaleInventory inventory;
        private final SaleService sales;

        InventoryWarmup(SaleCatalog catalog, SaleInventory inventory, SaleService sales) {
            this.catalog = catalog;
            this.inventory = inventory;
            this.sales = sales;
        }

        @EventListener(ApplicationReadyEvent.class)
        void warmUp() {
            try {
                for (Sale sale : catalog.notEnded()) {
                    if (!inventory.isInitialised(sale.getId())) {
                        sales.rebuildInventory(sale.getId());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Sale inventory warm-up skipped: {}", e.toString());
            }
        }
    }
}
