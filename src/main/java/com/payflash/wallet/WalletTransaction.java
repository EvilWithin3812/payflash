package com.payflash.wallet;

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
@Table(name = "transactions")
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID reference = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "from_wallet_id")
    private Long fromWalletId;

    @Column(name = "to_wallet_id")
    private Long toWalletId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    private String note;

    @Column(name = "initiated_by", nullable = false)
    private Long initiatedBy;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WalletTransaction() {
    }

    public WalletTransaction(TransactionType type, TransactionStatus status, Long fromWalletId, Long toWalletId,
                             BigDecimal amount, String note, Long initiatedBy, String idempotencyKey) {
        this.type = type;
        this.status = status;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.note = note;
        this.initiatedBy = initiatedBy;
        this.idempotencyKey = idempotencyKey;
    }

    /** True if a retried request carries the same payload as the original one. */
    public boolean matches(TransactionType type, Long toWalletId, BigDecimal amount) {
        return this.type == type && java.util.Objects.equals(this.toWalletId, toWalletId) && this.amount.compareTo(amount) == 0;
    }

    public Long getId() {
        return id;
    }

    public UUID getReference() {
        return reference;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Long getFromWalletId() {
        return fromWalletId;
    }

    public Long getToWalletId() {
        return toWalletId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getNote() {
        return note;
    }

    public Long getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
