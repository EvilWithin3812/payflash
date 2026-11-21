package com.payflash.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    static final String TOPIC = "wallet.transactions";
    static final String DLT = TOPIC + ".DLT";
    static final String ACCOUNTS_TOPIC = "user.accounts";

    /** The dead-letter topic belongs to the consumer that fills it. Same partition count as the source topic. */
    @Bean
    NewTopic walletTransactionsDlt() {
        return TopicBuilder.name(DLT).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic userAccountsDlt() {
        return TopicBuilder.name(ACCOUNTS_TOPIC + ".DLT").partitions(3).replicas(1).build();
    }

    /**
     * Retries a failing record with exponential backoff (1s, 2s, 4s), then publishes it to the DLT so one
     * bad event can't block its partition. Malformed JSON never succeeds, so it skips the retries.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxAttempts(3);
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }

    @Component
    static class TransactionEventListener {

        private static final Logger log = LoggerFactory.getLogger(TransactionEventListener.class);

        private final NotificationService notifications;
        private final ObjectMapper mapper;

        TransactionEventListener(NotificationService notifications, ObjectMapper mapper) {
            this.notifications = notifications;
            this.mapper = mapper;
        }

        @KafkaListener(topics = TOPIC, groupId = "${app.notifications.consumer-group:payflash-notifications}")
        void onTransaction(ConsumerRecord<String, String> record) throws JsonProcessingException {
            TransactionEvent event = mapper.readValue(record.value(), TransactionEvent.class);
            log.debug("Notifying for transaction {} (partition {}, offset {})", event.reference(), record.partition(),
                    record.offset());
            notifications.handle(event);
        }
    }

    /** When a user deletes their account, the core app tells us here; we delete everything we hold about them. */
    @Component
    static class AccountEventListener {

        private static final Logger log = LoggerFactory.getLogger(AccountEventListener.class);

        private final NotificationRepository repository;
        private final ObjectMapper mapper;

        AccountEventListener(NotificationRepository repository, ObjectMapper mapper) {
            this.repository = repository;
            this.mapper = mapper;
        }

        @KafkaListener(topics = ACCOUNTS_TOPIC, groupId = "${app.notifications.consumer-group:payflash-notifications}")
        void onAccountEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
            var node = mapper.readTree(record.value());
            if (!node.hasNonNull("userId")) {
                return;
            }
            long userId = node.get("userId").asLong();
            // Idempotent: a redelivered event simply deletes nothing.
            int deleted = repository.deleteAllForUser(userId);
            log.info("Account {} deleted: removed {} notifications", userId, deleted);
        }
    }
}
