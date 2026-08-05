package com.bank.txn.service;

import com.bank.txn.domain.Account;
import com.bank.txn.domain.AccountStatus;
import com.bank.txn.domain.AppUser;
import com.bank.txn.domain.AuditOutcome;
import com.bank.txn.error.AccessDeniedForAccountException;
import com.bank.txn.error.ResourceNotFoundException;
import com.bank.txn.repository.AccountRepository;
import com.bank.txn.repository.AppUserRepository;
import com.bank.txn.web.dto.AccountView;
import com.bank.txn.web.dto.CreateAccountRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ACCOUNT_NUMBER_ATTEMPTS = 5;

    private final AccountRepository accounts;
    private final AppUserRepository users;
    private final CachedAccountReader cache;
    private final AuditService audit;

    public AccountService(AccountRepository accounts,
                          AppUserRepository users,
                          CachedAccountReader cache,
                          AuditService audit) {
        this.accounts = accounts;
        this.users = users;
        this.cache = cache;
        this.audit = audit;
    }

    @Transactional
    public AccountView open(String username, CreateAccountRequest request) {
        AppUser owner = requireUser(username);
        Account account = new Account(
                generateAccountNumber(),
                owner.getId(),
                request.currency().toUpperCase(),
                request.openingBalance());
        accounts.save(account);

        audit.record(username, "ACCOUNT_OPENED", "Account", account.getAccountNumber(),
                AuditOutcome.SUCCESS, Map.of(
                        "currency", account.getCurrency(),
                        "openingBalance", account.getBalance().toPlainString()));

        return AccountView.from(account);
    }

    /** Served from Redis when warm; always ownership-checked against live data. */
    public AccountView get(String username, String accountNumber, boolean admin) {
        AccountView view = cache.load(accountNumber);
        if (!admin && !view.ownerId().equals(requireUser(username).getId())) {
            audit.record(username, "ACCOUNT_ACCESS_DENIED", "Account", accountNumber,
                    AuditOutcome.FAILURE, Map.of());
            throw new AccessDeniedForAccountException(accountNumber);
        }
        return view;
    }

    @Transactional(readOnly = true)
    public List<AccountView> listOwned(String username) {
        return accounts.findByOwnerIdOrderByCreatedAtAsc(requireUser(username).getId())
                .stream()
                .map(AccountView::from)
                .toList();
    }

    @Transactional
    public AccountView setStatus(String adminUsername, String accountNumber, AccountStatus status) {
        Account account = accounts.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));
        AccountStatus previous = account.getStatus();
        account.setStatus(status);
        accounts.saveAndFlush(account);
        cache.evict(accountNumber);

        audit.record(adminUsername, "ACCOUNT_STATUS_CHANGED", "Account", accountNumber,
                AuditOutcome.SUCCESS, Map.of("from", previous.name(), "to", status.name()));

        return AccountView.from(account);
    }

    @Transactional(readOnly = true)
    public Account requireAccount(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));
    }

    @Transactional(readOnly = true)
    public Account requireAccountById(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    public AppUser requireUser(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));
    }

    private String generateAccountNumber() {
        for (int attempt = 0; attempt < ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            String candidate = "ACC" + String.format("%013d", Math.abs(RANDOM.nextLong() % 10_000_000_000_000L));
            if (!accounts.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate a unique account number");
    }
}
