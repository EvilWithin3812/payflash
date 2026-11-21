package com.payflash;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the full app against a real PostgreSQL and Redis in Docker (Testcontainers).
 * The containers are shared by all test classes for speed.
 *
 * <p>Kafka publishing, the sale order processor and rate limiting are off by default so each test only
 * exercises what it is about; {@code RateLimitTest} and {@code AbstractKafkaIntegrationTest} switch them on.
 */
@SpringBootTest(properties = {
        "app.outbox.publisher-enabled=false",
        "management.tracing.enabled=false",
        "app.sales.order-processor-enabled=false",
        "app.rate-limit.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "app.payments.simulated-topup-enabled=true",
        "app.mail.enabled=false",
        // Most tests are about money, not email: AccountFlowTest switches verification back on.
        "app.auth.require-verified-email=false"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }
}
