package com.payflash.wallet;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class WalletDtos {

    private WalletDtos() {
    }

    public record WalletResponse(Long walletId, String currency, BigDecimal balance, Instant updatedAt) {

        static WalletResponse from(Wallet w) {
            return new WalletResponse(w.getId(), w.getCurrency(), w.getBalance(), w.getUpdatedAt());
        }
    }

    public record TopUpRequest(
            @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 15, fraction = 2)
            BigDecimal amount) {
    }

    public record TransferRequest(
            @NotBlank @Email String recipientEmail,
            @NotNull @DecimalMin("0.01") @DecimalMax("100000.00") @Digits(integer = 15, fraction = 2)
            BigDecimal amount,
            @Size(max = 255) String note) {
    }

    /** A transaction seen from the perspective of the calling user's wallet. */
    public record TransactionResponse(
            UUID reference,
            TransactionType type,
            TransactionStatus status,
            Direction direction,
            BigDecimal amount,
            Long counterpartyWalletId,
            String note,
            Instant createdAt) {

        static TransactionResponse from(WalletTransaction t, Long myWalletId) {
            boolean outgoing = myWalletId.equals(t.getFromWalletId());
            return new TransactionResponse(
                    t.getReference(), t.getType(), t.getStatus(),
                    outgoing ? Direction.DEBIT : Direction.CREDIT,
                    t.getAmount(),
                    outgoing ? t.getToWalletId() : t.getFromWalletId(),
                    t.getNote(), t.getCreatedAt());
        }
    }

    public record LedgerEntryResponse(Long id, Long transactionId, Direction direction, BigDecimal amount,
                                      BigDecimal balanceAfter, Instant createdAt) {

        static LedgerEntryResponse from(LedgerEntry e) {
            return new LedgerEntryResponse(e.getId(), e.getTransactionId(), e.getDirection(), e.getAmount(),
                    e.getBalanceAfter(), e.getCreatedAt());
        }
    }

    /** Result of a money movement; replayed = true when served from an earlier request with the same key. */
    public record MoneyMovementResult(TransactionResponse transaction, boolean replayed) {
    }

    public record WalletMismatch(Long walletId, BigDecimal cachedBalance, BigDecimal ledgerBalance) {
    }

    public record ReconciliationReport(boolean healthy, int mismatchCount, java.util.List<WalletMismatch> mismatches) {
    }
}
