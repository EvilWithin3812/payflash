package com.payflash.notifications;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.payflash.notifications.TransactionEvent.Party;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * This service's view of the payment.requests event: someone asked for money (REQUESTED), or a request was
 * DECLINED or CANCELLED. Paying a request is a normal transfer and arrives on wallet.transactions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRequestEvent(
        UUID reference,
        String kind,
        Long requestId,
        BigDecimal amount,
        String currency,
        String note,
        Party requester,
        Party payer,
        Instant occurredAt) {
}
