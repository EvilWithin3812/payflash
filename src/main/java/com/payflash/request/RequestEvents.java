package com.payflash.request;

import com.payflash.wallet.WalletEvents.Party;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Event payloads on the payment.requests topic (the notification service emails the right person). */
public final class RequestEvents {

    private RequestEvents() {
    }

    /**
     * {@code kind}: REQUESTED (tell the payer), DECLINED (tell the requester) or CANCELLED (tell the payer).
     * Paying a request is an ordinary transfer, which already notifies both people.
     * {@code reference} is unique per event, so the consumer can drop duplicates.
     */
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
}
