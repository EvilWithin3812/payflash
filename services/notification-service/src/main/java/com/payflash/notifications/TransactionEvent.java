package com.payflash.notifications;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * This service's own view of the wallet.transactions event. The only thing shared with the core app is
 * this JSON contract, not Java classes. Unknown fields are ignored, so the producer can add fields
 * without breaking this consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionEvent(
        UUID reference,
        String type,
        Long fromWalletId,
        Long toWalletId,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        Party from,
        Party to,
        String note) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Party(Long userId, String email, String fullName) {
    }
}
