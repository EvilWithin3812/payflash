package com.payflash.wallet;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    /**
     * Fetches only the id. Money-moving code must NOT load the Wallet entity before locking it:
     * Hibernate would return that stale, already-managed instance from the persistence context
     * instead of the freshly locked row, causing lost updates / optimistic-lock failures.
     */
    @Query("""
            select new com.payflash.wallet.WalletEvents$Party(u.id, u.email, u.fullName)
            from Wallet w join User u on u.id = w.userId
            where w.id = :walletId
            """)
    Optional<WalletEvents.Party> findParty(@Param("walletId") Long walletId);

    /** Owner of a wallet, for showing who is on the other side of a transfer. */
    record WalletOwner(Long walletId, String email, String fullName, boolean deleted) {
    }

    /** Owners of several wallets in one query (one page of history needs at most a page of them). */
    @Query("""
            select new com.payflash.wallet.WalletRepository$WalletOwner(
                       w.id, u.email, u.fullName, case when u.deletedAt is not null then true else false end)
            from Wallet w join User u on u.id = w.userId
            where w.id in :walletIds
            """)
    List<WalletOwner> findOwners(@Param("walletIds") Collection<Long> walletIds);

    /** Recent transfer recipients of a wallet, newest first, skipping deleted accounts. */
    @Query(value = """
            select u.full_name as fullName, u.email as email
            from (select t.to_wallet_id, max(t.created_at) as last_sent
                  from transactions t
                  where t.from_wallet_id = :walletId and t.type = 'TRANSFER'
                  group by t.to_wallet_id) recent
            join wallets w on w.id = recent.to_wallet_id
            join users u on u.id = w.user_id
            where u.deleted_at is null
            order by recent.last_sent desc
            limit :limit
            """, nativeQuery = true)
    List<RecentRecipient> findRecentRecipients(@Param("walletId") Long walletId, @Param("limit") int limit);

    interface RecentRecipient {
        String getFullName();

        String getEmail();
    }

    @Query("select w.id from Wallet w where w.userId = :userId")
    Optional<Long> findIdByUserId(@Param("userId") Long userId);

    /**
     * SELECT ... FOR UPDATE on the given wallets, always in ascending id order.
     * A consistent lock order prevents deadlocks when A->B and B->A transfers run concurrently.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id in :ids order by w.id")
    List<Wallet> lockAllByIdOrdered(@Param("ids") Collection<Long> ids);

    /** Wallets whose cached balance does not equal (credits - debits) in the ledger. */
    @Query(value = """
            select w.id as walletId, w.balance as balance,
                   coalesce(sum(case when l.direction = 'CREDIT' then l.amount else -l.amount end), 0) as ledgerBalance
            from wallets w
            left join ledger_entries l on l.wallet_id = w.id
            group by w.id, w.balance
            having w.balance <> coalesce(sum(case when l.direction = 'CREDIT' then l.amount else -l.amount end), 0)
            """, nativeQuery = true)
    List<ReconciliationMismatch> findReconciliationMismatches();

    interface ReconciliationMismatch {
        Long getWalletId();

        java.math.BigDecimal getBalance();

        java.math.BigDecimal getLedgerBalance();
    }
}
