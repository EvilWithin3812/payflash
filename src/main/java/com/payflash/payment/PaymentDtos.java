package com.payflash.payment;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreateTopUpOrderRequest(
            @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 15, fraction = 2)
            BigDecimal amount) {
    }

    /** Everything the browser needs to open Razorpay Checkout. keyId is public by design; the secret never leaves the server. */
    public record TopUpOrderResponse(
            String orderId,
            BigDecimal amount,
            long amountMinor,
            String currency,
            PaymentOrder.Status status,
            String keyId,
            UUID transactionReference,
            Instant createdAt) {

        static TopUpOrderResponse from(PaymentOrder o, String keyId) {
            return new TopUpOrderResponse(o.getProviderOrderId(), o.getAmount(), PaymentService.toMinor(o.getAmount()),
                    o.getCurrency(), o.getStatus(), keyId, o.getTransactionReference(), o.getCreatedAt());
        }
    }

    /** The three values Razorpay Checkout passes to its success handler. */
    public record VerifyPaymentRequest(
            @NotBlank String razorpayOrderId,
            @NotBlank String razorpayPaymentId,
            @NotBlank String razorpaySignature) {
    }
}
