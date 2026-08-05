package com.bank.txn.web.dto;

import com.bank.txn.domain.Account;
import com.bank.txn.domain.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountView(UUID id,
                          String accountNumber,
                          UUID ownerId,
                          String currency,
                          BigDecimal balance,
                          AccountStatus status,
                          long version,
                          Instant createdAt,
                          Instant updatedAt) {

    public static AccountView from(Account account) {
        return new AccountView(
                account.getId(),
                account.getAccountNumber(),
                account.getOwnerId(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus(),
                account.getVersion(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
