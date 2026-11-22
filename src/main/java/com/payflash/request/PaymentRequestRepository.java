package com.payflash.request;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, Long> {

    /** SELECT ... FOR UPDATE: two "Pay" clicks on the same request run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRequest r where r.id = :id")
    Optional<PaymentRequest> lockById(@Param("id") Long id);

    Page<PaymentRequest> findByPayerId(Long payerId, Pageable pageable);

    Page<PaymentRequest> findByRequesterId(Long requesterId, Pageable pageable);

    long countByPayerIdAndStatus(Long payerId, PaymentRequestStatus status);

    long countByRequesterIdAndStatus(Long requesterId, PaymentRequestStatus status);

    /** Account deletion: open requests to or from the user can no longer be paid. */
    @Modifying
    @Query("""
            update PaymentRequest r set r.status = com.payflash.request.PaymentRequestStatus.CANCELLED,
                   r.resolvedAt = :now
            where r.status = com.payflash.request.PaymentRequestStatus.PENDING
              and (r.requesterId = :userId or r.payerId = :userId)
            """)
    int cancelAllPendingFor(@Param("userId") Long userId, @Param("now") Instant now);
}
