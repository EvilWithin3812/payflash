package com.payflash.wallet;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Optional<WalletTransaction> findByInitiatedByAndIdempotencyKey(Long initiatedBy, String idempotencyKey);

    @Query("select t from WalletTransaction t where t.fromWalletId = :walletId or t.toWalletId = :walletId")
    Page<WalletTransaction> findAllForWallet(@Param("walletId") Long walletId, Pageable pageable);
}
