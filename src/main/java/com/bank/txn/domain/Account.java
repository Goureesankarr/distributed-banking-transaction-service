package com.bank.txn.domain;

import com.bank.txn.error.AccountNotActiveException;
import com.bank.txn.error.CurrencyMismatchException;
import com.bank.txn.error.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer account.
 *
 * <p>Concurrency is handled optimistically: {@link #version} is bumped by
 * Hibernate on every flush, so if two transactions read the same balance and
 * both try to write, the second commit fails with an optimistic lock error and
 * is retried against fresh state rather than silently overwriting the first.
 * No row locks are held across the transfer, which keeps hot accounts from
 * serialising the whole service.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 24)
    private String accountNumber;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Account() {
    }

    public Account(String accountNumber, UUID ownerId, String currency, BigDecimal openingBalance) {
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.currency = currency;
        this.balance = openingBalance;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Removes {@code amount} from this account, refusing to go negative. */
    public void debit(BigDecimal amount, String txnCurrency) {
        requireUsable(txnCurrency);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountNumber, balance, amount);
        }
        this.balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount, String txnCurrency) {
        requireUsable(txnCurrency);
        this.balance = balance.add(amount);
    }

    private void requireUsable(String txnCurrency) {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(accountNumber, status);
        }
        if (!currency.equalsIgnoreCase(txnCurrency)) {
            throw new CurrencyMismatchException(accountNumber, currency, txnCurrency);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
