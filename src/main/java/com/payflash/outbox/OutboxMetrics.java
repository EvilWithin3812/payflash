package com.payflash.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OutboxMetrics {

    /**
     * Events written but not yet published to Kafka. It should hover near zero; a growing backlog means Kafka
     * is down or the publisher is stuck. Read on each Prometheus scrape; the partial index on
     * published_at IS NULL keeps the count cheap.
     */
    @Bean
    MeterBinder outboxBacklog(OutboxRepository repository) {
        return registry -> Gauge.builder("payflash.outbox.pending", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet published to Kafka")
                .register(registry);
    }
}
