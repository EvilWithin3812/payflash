package com.payflash.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The address for an uptime monitor (UptimeRobot, Better Stack, ...): https://your-site/api/v1/health.
 * A request travels nginx → gateway → app → database, Redis and Kafka, so a 200 means the whole chain works.
 * Only "UP" or "DOWN" is returned, never details about the components.
 */
@RestController
@Tag(name = "Health")
public class PublicHealthController {

    private final HealthEndpoint health;

    public PublicHealthController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping("/api/v1/health")
    @Operation(summary = "Public up/down check for uptime monitors")
    public ResponseEntity<Map<String, String>> health() {
        boolean up = Status.UP.equals(health.health().getStatus());
        return ResponseEntity.status(up ? 200 : 503).body(Map.of("status", up ? "UP" : "DOWN"));
    }
}
