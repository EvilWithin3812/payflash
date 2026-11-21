package com.payflash.wallet;

import com.payflash.auth.UserRepository;
import com.payflash.common.BusinessException;
import com.payflash.outbox.OutboxService;
import com.payflash.outbox.Topics;
import com.payflash.wallet.WalletDtos.LedgerEntryResponse;
import com.payflash.wallet.WalletDtos.MoneyMovementResult;
import com.payflash.wallet.WalletDtos.ReconciliationReport;
import com.payflash.wallet.WalletDtos.TransactionResponse;
import com.payflash.wallet.WalletDtos.TransferRequest;
import com.payflash.wallet.WalletDtos.WalletMismatch;
import com.payflash.wallet.WalletDtos.WalletResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core money-movement logic.
 *
 * <p>Correctness guarantees:
 * <ul>
 *   <li><b>No lost updates</b> - wallets are row-locked (SELECT ... FOR UPDATE) in ascending id order,
 *       which also prevents deadlocks between opposite-direction transfers.</li>
 *   <li><b>Exactly-once effect</b> - every request carries an Idempotency-Key; the key is checked
 *       <i>after</i> the sender's lock is held, so concurrent retries are serialized and the second one
 *       sees the first one's committed transaction.</li>
 *   <li><b>Auditability</b> - each movement writes balanced DEBIT/CREDIT ledger rows; cached balances
 *       can be reconciled against the ledger at any time.</li>
 *   <li><b>Reliable events</b> - a TransactionCompleted event is written to the outbox in the same DB
 *       transaction, then published to Kafka asynchronously.</li>
 * </ul>
 */
@Service
public class WalletService {

    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;
    private final LedgerEntryRepository ledger;
    private final UserRepository users;
    private final OutboxService outbox;
    private final MeterRegistry metrics;

    public WalletService(WalletRepository wallets, WalletTransactionRepository transactions,
                         LedgerEntryRepository ledger, UserRepository users, OutboxService outbox, MeterRegistry metrics) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.ledger = ledger;
        this.users = users;
        this.outbox = outbox;
        this.metrics = metrics;
    }

    @Transactional
    public Wallet createWalletFor(Long userId) {
        return wallets.save(new Wallet(userId));
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(Long userId) {
        return WalletResponse.from(walletOf(userId));
    }

    /** The balance, read with the wallet row locked until the caller's transaction ends. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public BigDecimal lockedBalance(Long userId) {
        return wallets.lockAllByIdOrdered(List.of(walletIdOf(userId))).getFirst().getBalance();
    }

    /** Simulated gateway top-up (dev/demo only; see app.payments.simulated-topup-enabled). */
    @Transactional
    public MoneyMovementResult topUp(Long userId, BigDecimal amount, String idempotencyKey) {
        return topUp(userId, amount, idempotencyKey, "Wallet top-up");
    }

    /**
     * Credits the user's wallet with money received from outside (a payment gateway).
     * Joins the caller's transaction, so a Razorpay order is marked PAID atomically with the credit.
     */
    @Transactional
    public MoneyMovementResult topUp(Long userId, BigDecimal amount, String idempotencyKey, String note) {
        validateKey(idempotencyKey);
        Long walletId = walletIdOf(userId);
        Wallet wallet = wallets.lockAllByIdOrdered(List.of(walletId)).getFirst();

        var existing = findReplay(userId, idempotencyKey, TransactionType.TOPUP, walletId, amount);
        if (existing != null) {
            return new MoneyMovementResult(TransactionResponse.from(existing, walletId), true);
        }

        wallet.credit(amount);
        WalletTransaction tx = transactions.save(new WalletTransaction(TransactionType.TOPUP,
                TransactionStatus.COMPLETED, null, walletId, amount, note, userId, idempotencyKey));

        // Double entry: money leaves the external (gateway) account and enters the wallet.
        ledger.save(new LedgerEntry(tx.getId(), null, Direction.DEBIT, amount, null));
        ledger.save(new LedgerEntry(tx.getId(), walletId, Direction.CREDIT, amount, wallet.getBalance()));

        publishCompleted(tx, wallet.getCurrency());
        return new MoneyMovementResult(TransactionResponse.from(tx, walletId), false);
    }

    @Transactional
    public MoneyMovementResult transfer(Long senderUserId, TransferRequest req, String idempotencyKey) {
        validateKey(idempotencyKey);
        BigDecimal amount = req.amount();
        Long fromId = walletIdOf(senderUserId);
        Long toId = users.findByEmailIgnoreCase(req.recipientEmail().trim())
                .filter(u -> !u.isDeleted())
                .flatMap(u -> wallets.findIdByUserId(u.getId()))
                .orElseThrow(() -> BusinessException.notFound("Recipient not found"));
        if (fromId.equals(toId)) {
            throw BusinessException.badRequest("SELF_TRANSFER", "Cannot transfer to your own wallet");
        }

        // Lock both rows in a consistent (ascending id) order to avoid deadlocks.
        Map<Long, Wallet> locked = wallets.lockAllByIdOrdered(List.of(fromId, toId)).stream()
                .collect(Collectors.toMap(Wallet::getId, Function.identity()));
        Wallet from = locked.get(fromId);
        Wallet to = locked.get(toId);

        var existing = findReplay(senderUserId, idempotencyKey, TransactionType.TRANSFER, toId, amount);
        if (existing != null) {
            return new MoneyMovementResult(TransactionResponse.from(existing, fromId), true);
        }

        from.debit(amount); // throws INSUFFICIENT_FUNDS -> whole transaction rolls back
        to.credit(amount);

        WalletTransaction tx = transactions.save(new WalletTransaction(TransactionType.TRANSFER,
                TransactionStatus.COMPLETED, fromId, toId, amount, req.note(), senderUserId, idempotencyKey));
        ledger.save(new LedgerEntry(tx.getId(), fromId, Direction.DEBIT, amount, from.getBalance()));
        ledger.save(new LedgerEntry(tx.getId(), toId, Direction.CREDIT, amount, to.getBalance()));

        publishCompleted(tx, from.getCurrency());
        return new MoneyMovementResult(TransactionResponse.from(tx, fromId), false);
    }

    /**
     * Pays a merchant from the user's wallet (flash-sale orders). Double entry: the wallet is debited and the
     * external merchant account (wallet_id NULL) is credited. Joins the caller's transaction so the order
     * is marked PAID atomically with the debit.
     */
    @Transactional
    public MoneyMovementResult purchase(Long userId, BigDecimal amount, String idempotencyKey, String note) {
        validateKey(idempotencyKey);
        Long walletId = walletIdOf(userId);
        Wallet wallet = wallets.lockAllByIdOrdered(List.of(walletId)).getFirst();

        var existing = findReplay(userId, idempotencyKey, TransactionType.PURCHASE, null, amount);
        if (existing != null) {
            return new MoneyMovementResult(TransactionResponse.from(existing, walletId), true);
        }

        wallet.debit(amount); // INSUFFICIENT_FUNDS -> caller's transaction rolls back
        WalletTransaction tx = transactions.save(new WalletTransaction(TransactionType.PURCHASE,
                TransactionStatus.COMPLETED, walletId, null, amount, note, userId, idempotencyKey));
        ledger.save(new LedgerEntry(tx.getId(), walletId, Direction.DEBIT, amount, wallet.getBalance()));
        ledger.save(new LedgerEntry(tx.getId(), null, Direction.CREDIT, amount, null));

        publishCompleted(tx, wallet.getCurrency());
        return new MoneyMovementResult(TransactionResponse.from(tx, walletId), false);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> history(Long userId, int page, int size) {
        Long walletId = walletOf(userId).getId();
        var pageable = PageRequest.of(page, clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return transactions.findAllForWallet(walletId, pageable).map(t -> TransactionResponse.from(t, walletId));
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> ledger(Long userId, int page, int size) {
        Long walletId = walletOf(userId).getId();
        var pageable = PageRequest.of(page, clampSize(size), Sort.by(Sort.Direction.DESC, "id"));
        return ledger.findByWalletId(walletId, pageable).map(LedgerEntryResponse::from);
    }

    /** Verifies every cached wallet balance equals SUM(credits) - SUM(debits) in the ledger. */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        List<WalletMismatch> mismatches = wallets.findReconciliationMismatches().stream()
                .map(m -> new WalletMismatch(m.getWalletId(), m.getBalance(), m.getLedgerBalance()))
                .toList();
        return new ReconciliationReport(mismatches.isEmpty(), mismatches.size(), mismatches);
    }

    // ------------------------------------------------------------------ helpers

    private Long walletIdOf(Long userId) {
        return wallets.findIdByUserId(userId).orElseThrow(() -> BusinessException.notFound("Wallet not found"));
    }

    private Wallet walletOf(Long userId) {
        return wallets.findByUserId(userId).orElseThrow(() -> BusinessException.notFound("Wallet not found"));
    }

    /** Returns the earlier transaction for this key, or null. Rejects key reuse with a different payload. */
    private WalletTransaction findReplay(Long userId, String key, TransactionType type, Long toWalletId,
                                         BigDecimal amount) {
        return transactions.findByInitiatedByAndIdempotencyKey(userId, key)
                .map(tx -> {
                    if (!tx.matches(type, toWalletId, amount)) {
                        throw BusinessException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                                "Idempotency-Key was already used for a different request");
                    }
                    return tx;
                })
                .orElse(null);
    }

    private void publishCompleted(WalletTransaction tx, String currency) {
        // Event-carried state: the people involved travel with the event, so consumers (the notification
        // service) never need to read this service's database.
        var event = new WalletEvents.TransactionCompleted(tx.getReference(), tx.getType(), tx.getFromWalletId(),
                tx.getToWalletId(), tx.getAmount(), currency, Instant.now(),
                partyOf(tx.getFromWalletId()), partyOf(tx.getToWalletId()), tx.getNote());
        outbox.record("Transaction", tx.getReference().toString(), "TransactionCompleted",
                Topics.WALLET_TRANSACTIONS, event);
        metrics.counter("payflash.wallet.transactions", "type", tx.getType().name()).increment();
    }

    private WalletEvents.Party partyOf(Long walletId) {
        return walletId == null ? null : wallets.findParty(walletId).orElse(null);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw BusinessException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header must be 1-100 characters");
        }
    }

    private static int clampSize(int size) {
        return Math.max(1, Math.min(size, 100));
    }
}
