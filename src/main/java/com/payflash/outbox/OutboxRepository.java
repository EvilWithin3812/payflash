package com.payflash.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of unpublished events. SKIP LOCKED lets several app instances
     * poll the same table without publishing the same event twice.
     */
    @Query(value = """
            select * from outbox_events
            where published_at is null
            order by id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> claimBatch(@Param("limit") int limit);

    long countByPublishedAtIsNull();
}
