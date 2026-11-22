package com.payflash.request;

import com.payflash.common.BusinessException;
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

/** "Please pay me": {@code requester} asks {@code payer} for {@code amount}. */
@Entity
@Table(name = "payment_requests")
public class PaymentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private Long requesterId;

    @Column(name = "payer_id", nullable = false, updatable = false)
    private Long payerId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentRequestStatus status = PaymentRequestStatus.PENDING;

    @Column(name = "split_group", updatable = false)
    private UUID splitGroup;

    @Column(name = "transaction_reference")
    private UUID transactionReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected PaymentRequest() {
        // for JPA
    }

    public PaymentRequest(Long requesterId, Long payerId, BigDecimal amount, String note, UUID splitGroup) {
        this.requesterId = requesterId;
        this.payerId = payerId;
        this.amount = amount;
        this.note = note;
        this.splitGroup = splitGroup;
    }

    public void markPaid(UUID transactionReference) {
        requirePending();
        this.status = PaymentRequestStatus.PAID;
        this.transactionReference = transactionReference;
        this.resolvedAt = Instant.now();
    }

    public void decline() {
        requirePending();
        this.status = PaymentRequestStatus.DECLINED;
        this.resolvedAt = Instant.now();
    }

    public void cancel() {
        requirePending();
        this.status = PaymentRequestStatus.CANCELLED;
        this.resolvedAt = Instant.now();
    }

    private void requirePending() {
        if (status != PaymentRequestStatus.PENDING) {
            throw BusinessException.conflict("REQUEST_NOT_PENDING",
                    "This request is already " + status.name().toLowerCase());
        }
    }

    public Long getId() {
        return id;
    }

    public Long getRequesterId() {
        return requesterId;
    }

    public Long getPayerId() {
        return payerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getNote() {
        return note;
    }

    public PaymentRequestStatus getStatus() {
        return status;
    }

    public UUID getSplitGroup() {
        return splitGroup;
    }

    public UUID getTransactionReference() {
        return transactionReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
