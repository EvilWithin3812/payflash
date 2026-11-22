package com.payflash.request;

import com.payflash.wallet.WalletDtos.Counterparty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RequestDtos {

    private RequestDtos() {
    }

    public enum Direction {
        /** Someone asked me to pay. */
        INCOMING,
        /** I asked someone to pay me. */
        OUTGOING
    }

    public record CreateRequest(
            @NotBlank @Email String payerEmail,
            @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 15, fraction = 2)
            BigDecimal amount,
            @Size(max = 255) String note) {
    }

    /**
     * Splits {@code totalAmount} equally. With {@code includeMe}, the requester is one of the people sharing the
     * bill (and pays their own share themselves); otherwise the friends share all of it.
     */
    public record SplitRequest(
            @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 15, fraction = 2)
            BigDecimal totalAmount,
            @Size(max = 255) String note,
            @NotEmpty @Size(max = 10) List<@NotBlank @Email String> friendEmails,
            boolean includeMe) {
    }

    /** {@code pin}: the transaction PIN, needed when the amount is above the PIN threshold. */
    public record PayRequest(@Size(max = 6) String pin) {
    }

    /** A request seen by one of the two people; {@code otherParty} is the person on the other side. */
    public record RequestResponse(
            Long id,
            Direction direction,
            PaymentRequestStatus status,
            BigDecimal amount,
            String note,
            Counterparty otherParty,
            UUID splitGroup,
            UUID transactionReference,
            Instant createdAt,
            Instant resolvedAt) {
    }

    public record SplitResponse(UUID splitGroup, BigDecimal totalAmount, BigDecimal myShare,
                                List<RequestResponse> requests) {
    }

    /** For the "Requests" badge: how many requests are waiting for me to pay, and for others to pay me. */
    public record RequestSummary(long waitingForMe, long waitingForOthers) {
    }
}
