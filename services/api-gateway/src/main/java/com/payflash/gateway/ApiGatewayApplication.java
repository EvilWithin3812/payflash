package com.payflash.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The single entry point in front of the PayFlash services. Clients never need to know which service owns
 * which endpoint: routes live in application.yml. Each route is wrapped in a circuit breaker so a dead
 * service fails fast with a clear 503 instead of hanging every caller.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
