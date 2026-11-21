package com.payflash.payment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByProviderOrderId(String providerOrderId);

    Optional<PaymentOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * SELECT ... FOR UPDATE on the order row. The browser's verify call and Razorpay's webhook usually
     * arrive at the same moment; the lock makes the second one wait and then see status = PAID.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.providerOrderId = :orderId")
    Optional<PaymentOrder> lockByProviderOrderId(@Param("orderId") String orderId);
}
