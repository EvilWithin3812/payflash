package com.payflash.sale;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {

    Optional<SaleOrder> findByHoldToken(UUID holdToken);

    boolean existsByHoldToken(UUID holdToken);

    List<SaleOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countBySaleIdAndStatus(Long saleId, SaleOrder.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SaleOrder o where o.holdToken = :token")
    Optional<SaleOrder> lockByHoldToken(@Param("token") UUID token);

    /**
     * Claims expired holds. SKIP LOCKED: a hold that is being paid right now is locked by the pay
     * transaction and skipped, and several app instances can sweep in parallel without conflicts.
     */
    @Query(value = """
            select * from sale_orders
            where status = 'HELD' and expires_at <= now()
            order by expires_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<SaleOrder> claimExpiredHolds(@Param("limit") int limit);

    /** Units currently held or sold, used to rebuild the Redis pool from the database. */
    @Query("select o from SaleOrder o where o.saleId = :saleId and o.status in ('HELD', 'PAID')")
    List<SaleOrder> findLiveOrders(@Param("saleId") Long saleId);
}
