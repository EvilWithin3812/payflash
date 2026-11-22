package com.payflash.config;

import com.payflash.outbox.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic walletTransactionsTopic() {
        return TopicBuilder.name(Topics.WALLET_TRANSACTIONS).partitions(3).replicas(1).build();
    }

    /** 6 partitions: up to 6 order-processor threads (across instances) can drain a burst in parallel. */
    @Bean
    NewTopic saleOrdersTopic() {
        return TopicBuilder.name(Topics.SALE_ORDERS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic saleOrdersDltTopic() {
        return TopicBuilder.name(Topics.SALE_ORDERS_DLT).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic userAccountsTopic() {
        return TopicBuilder.name(Topics.USER_ACCOUNTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentRequestsTopic() {
        return TopicBuilder.name(Topics.PAYMENT_REQUESTS).partitions(3).replicas(1).build();
    }
}
