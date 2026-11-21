package com.payflash.notifications;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByEventReferenceAndUserIdAndChannel(UUID eventReference, Long userId, String channel);

    List<Notification> findByEventReference(UUID eventReference);

    List<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from Notification n where n.userId = :userId")
    int deleteAllForUser(Long userId);
}
