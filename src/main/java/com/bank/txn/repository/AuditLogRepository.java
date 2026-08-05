package com.bank.txn.repository;

import com.bank.txn.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByActorOrderByCreatedAtDesc(String actor, Pageable pageable);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);

    /** Used by the Kafka consumer to make at-least-once delivery effectively once. */
    boolean existsByEntityTypeAndEntityIdAndAction(String entityType, String entityId, String action);
}
