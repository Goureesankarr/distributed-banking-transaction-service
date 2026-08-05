package com.bank.txn.web.dto;

import com.bank.txn.domain.EntryDirection;
import com.bank.txn.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryView(UUID id,
                              UUID transferId,
                              UUID accountId,
                              EntryDirection direction,
                              BigDecimal amount,
                              BigDecimal balanceAfter,
                              Instant createdAt) {

    public static LedgerEntryView from(LedgerEntry entry) {
        return new LedgerEntryView(
                entry.getId(),
                entry.getTransferId(),
                entry.getAccountId(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                entry.getCreatedAt());
    }
}
