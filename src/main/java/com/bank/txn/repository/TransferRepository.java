package com.bank.txn.repository;

import com.bank.txn.domain.Transfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByReference(String reference);

    /**
     * Callers pass explicit bounds rather than nulls; an untyped null in a
     * {@code :param IS NULL OR ...} predicate is exactly the sort of thing
     * PostgreSQL refuses to infer a type for.
     */
    @Query("""
            SELECT t FROM Transfer t
            WHERE (t.sourceAccountId = :accountId OR t.targetAccountId = :accountId)
              AND t.createdAt >= :from
              AND t.createdAt <= :to
            ORDER BY t.createdAt DESC
            """)
    Page<Transfer> findHistory(@Param("accountId") UUID accountId,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               Pageable pageable);
}
