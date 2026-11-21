package com.payflash.sale;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class SaleEvents {

    private SaleEvents() {
    }

    /**
     * Published to sale.orders when Redis grants a unit. The API answers 202 immediately; the order
     * processor writes the row at its own pace, so a burst of 10k buyers doesn't become 10k
     * simultaneous Postgres transactions.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderRequested(UUID holdToken, Long saleId, Long userId, int unitNo, BigDecimal price,
                                 Instant expiresAt) {
    }
}
