package com.payflash.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox table and publishes events to Kafka (at-least-once delivery).
 * Consumers must therefore be idempotent - every event carries a unique transaction reference.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.publisher-enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxTracing tracing;
    private final int batchSize;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka, OutboxTracing tracing,
                           @Value("${app.outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.kafka = kafka;
        this.tracing = tracing;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.claimBatch(batchSize);
        for (OutboxEvent event : batch) {
            try {
                // Key by aggregate id so all events for one transaction land on the same partition (ordering).
                tracing.continueTrace(event.getTraceParent(), "outbox publish " + event.getEventType(), () -> {
                    try {
                        return kafka.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                                .get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                event.markPublished();
            } catch (Exception e) {
                log.warn("Kafka publish failed for outbox event {}; will retry next poll", event.getId(), e);
                return; // keep ordering: stop at first failure, already-sent events in this batch stay marked
            }
        }
        if (!batch.isEmpty()) {
            log.debug("Published {} outbox events", batch.size());
        }
    }
}
