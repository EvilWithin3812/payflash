package com.payflash.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/** Shared by every @KafkaListener in this app (the sale order processor). */
@Configuration
public class KafkaErrorHandlingConfig {

    /**
     * Picked up by Spring Boot's listener container factory. A failing record is retried with
     * exponential backoff (1s, 2s, 4s), then published to "&lt;topic&gt;.DLT" so one bad event can't
     * block its partition forever. Malformed JSON will never succeed, so it skips the retries.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxAttempts(3);
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }
}
