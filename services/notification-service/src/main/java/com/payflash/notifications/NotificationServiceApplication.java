package com.payflash.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification service, extracted from the PayFlash modular monolith.
 * It only talks to the rest of the system through Kafka (wallet.transactions) and owns its own database,
 * so it can be deployed, scaled and restarted independently.
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
