package com.payflash.gateway;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where an open circuit (or a timeout) sends the request. Uses the same JSON error shape as the services,
 * so the frontend shows a readable message instead of a proxy error page.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<Map<String, Object>> fallback(@PathVariable String service) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 503,
                "code", "SERVICE_UNAVAILABLE",
                "message", "The " + service + " service is temporarily unavailable. Please retry shortly.",
                "fieldErrors", Map.of()));
    }
}
