package com.bank.txn.repository;

import com.bank.txn.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of unpublished events. {@code FOR UPDATE SKIP LOCKED} lets
     * every service instance poll the same table concurrently while each row is
     * relayed by exactly one of them.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL
              AND attempts < :maxAttempts
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.publishedAt IS NULL")
    long countPending();
}
