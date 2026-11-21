package com.payflash.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Event payloads published to Kafka via the outbox. */
public final class WalletEvents {

    private WalletEvents() {
    }

    /**
     * from / to are null for the external side of a top-up (from) or a purchase (to).
     * Carries contact details for consumers; the topic is internal, so keep it off any public bus.
     */
    public record TransactionCompleted(
            UUID reference,
            TransactionType type,
            Long fromWalletId,
            Long toWalletId,
            BigDecimal amount,
            String currency,
            Instant occurredAt,
            Party from,
            Party to,
            String note) {
    }

    /** Owner of a wallet, as seen by event consumers. */
    public record Party(Long userId, String email, String fullName) {
    }
}
