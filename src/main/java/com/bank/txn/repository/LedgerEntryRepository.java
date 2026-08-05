package com.bank.txn.repository;

import com.bank.txn.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransferId(UUID transferId);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    /**
     * Reconciliation helper: across the whole ledger, credits and debits must
     * cancel out exactly. A non-zero result means money was created or lost.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN e.direction = com.bank.txn.domain.EntryDirection.CREDIT
                                     THEN e.amount ELSE -e.amount END), 0)
            FROM LedgerEntry e
            """)
    BigDecimal netPostedAmount();
}
