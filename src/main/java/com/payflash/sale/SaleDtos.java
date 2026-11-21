package com.payflash.sale;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class SaleDtos {

    private SaleDtos() {
    }

    public record CreateSaleRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String description,
            @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 15, fraction = 2) BigDecimal price,
            @Min(1) @Max(100_000) int totalUnits,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Min(30) @Max(3600) Integer holdSeconds) {
    }

    public record SaleResponse(Long id, String name, String description, BigDecimal price, int totalUnits,
                               long remainingUnits, Instant startsAt, Instant endsAt, int holdSeconds,
                               Sale.Phase phase) {

        static SaleResponse from(Sale s, long remaining, Instant now) {
            return new SaleResponse(s.getId(), s.getName(), s.getDescription(), s.getPrice(), s.getTotalUnits(),
                    remaining, s.getStartsAt(), s.getEndsAt(), s.getHoldSeconds(), s.phaseAt(now));
        }
    }

    /** PROCESSING = Redis granted the unit, the order row is still in the Kafka queue. */
    public enum OrderState { PROCESSING, HELD, PAID, EXPIRED }

    public record SaleOrderResponse(UUID holdToken, Long saleId, int unitNo, OrderState status, BigDecimal price,
                                    Instant expiresAt, UUID transactionReference, Instant createdAt) {

        static SaleOrderResponse from(SaleOrder o, Instant now) {
            OrderState state = switch (o.getStatus()) {
                case PAID -> OrderState.PAID;
                case EXPIRED -> OrderState.EXPIRED;
                case HELD -> o.isExpiredAt(now) ? OrderState.EXPIRED : OrderState.HELD;
            };
            return new SaleOrderResponse(o.getHoldToken(), o.getSaleId(), o.getUnitNo(), state, o.getPrice(),
                    o.getExpiresAt(), o.getTransactionReference(), o.getCreatedAt());
        }
    }

    /** Pushed over WebSocket to /topic/sales/{id}. */
    public record StockUpdate(Long saleId, long remainingUnits, int totalUnits) {
    }
}
