package com.payflash.sale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sale_orders")
public class SaleOrder {

    public enum Status { HELD, PAID, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_id", nullable = false, updatable = false)
    private Long saleId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "unit_no", nullable = false, updatable = false)
    private int unitNo;

    @Column(name = "hold_token", nullable = false, unique = true, updatable = false)
    private UUID holdToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal price;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "transaction_reference")
    private UUID transactionReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SaleOrder() {
    }

    public SaleOrder(Long saleId, Long userId, int unitNo, UUID holdToken, BigDecimal price, Instant expiresAt,
                     Status status) {
        this.saleId = saleId;
        this.userId = userId;
        this.unitNo = unitNo;
        this.holdToken = holdToken;
        this.price = price;
        this.expiresAt = expiresAt;
        this.status = status;
    }

    public boolean isExpiredAt(Instant now) {
        return status == Status.EXPIRED || (status == Status.HELD && !now.isBefore(expiresAt));
    }

    public void markPaid(UUID transactionReference) {
        this.status = Status.PAID;
        this.transactionReference = transactionReference;
        this.updatedAt = Instant.now();
    }

    public void markExpired() {
        this.status = Status.EXPIRED;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSaleId() {
        return saleId;
    }

    public Long getUserId() {
        return userId;
    }

    public int getUnitNo() {
        return unitNo;
    }

    public UUID getHoldToken() {
        return holdToken;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getTransactionReference() {
        return transactionReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
