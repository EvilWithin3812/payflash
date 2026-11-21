package com.payflash.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routes against a tiny fake "core" service (JDK HTTP server) and a notification service that is down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.enabled=false")
class GatewayRoutingTest {

    static final HttpServer CORE;
    static final AtomicReference<String> LAST_REQUEST_ID = new AtomicReference<>();
    static final AtomicReference<String> LAST_FORWARDED_FOR = new AtomicReference<>();

    static {
        try {
            CORE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        CORE.createContext("/api/v1/sales", exchange -> {
            LAST_REQUEST_ID.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            LAST_FORWARDED_FOR.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            byte[] body = "[{\"id\":1}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        CORE.start();
    }

    @AfterAll
    static void stopCore() {
        CORE.stop(0);
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("CORE_SERVICE_URL", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
        registry.add("NOTIFICATION_SERVICE_URL", () -> "http://127.0.0.1:1"); // nothing listens on port 1
    }

    @Autowired
    WebTestClient client;

    @Test
    void routesCoreApiAndAddsRequestId() {
        var result = client.get().uri("/api/v1/sales").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(String.class).returnResult();

        assertThat(result.getResponseBody()).isEqualTo("[{\"id\":1}]");
        assertThat(LAST_REQUEST_ID.get()).isEqualTo(result.getResponseHeaders().getFirst("X-Request-Id"));
        assertThat(LAST_FORWARDED_FOR.get()).isNotBlank(); // client IP reaches the service for rate limiting
    }

    @Test
    void keepsWellFormedClientRequestIdButReplacesUnsafeOnes() {
        client.get().uri("/api/v1/sales").header("X-Request-Id", "abc-123-def-456").exchange()
                .expectHeader().valueEquals("X-Request-Id", "abc-123-def-456");
        client.get().uri("/api/v1/sales").header("X-Request-Id", "bad value <script>").exchange()
                .expectHeader().value("X-Request-Id", id -> assertThat(id).hasSize(36));
    }

    @Test
    void deadServiceGetsJson503FromCircuitBreakerFallback() {
        client.get().uri("/api/v1/notifications").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").value(m -> assertThat((String) m).contains("notifications"));
    }
}
