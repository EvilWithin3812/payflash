package com.payflash;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Adds a real Kafka broker and switches on everything asynchronous: the outbox publisher and the
 * flash-sale order processor.
 * Every subclass shares one broker and one Spring context.
 */
@TestPropertySource(properties = {
        "app.outbox.publisher-enabled=true",
        "app.outbox.poll-interval-ms=200",
        "app.sales.order-processor-enabled=true",
        "spring.kafka.admin.auto-create=true"
})
public abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        KAFKA.start();
    }
}
