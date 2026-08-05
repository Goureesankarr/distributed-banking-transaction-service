package com.bank.txn.service;

import com.bank.txn.config.RedisConfig;
import com.bank.txn.error.ResourceNotFoundException;
import com.bank.txn.repository.AccountRepository;
import com.bank.txn.web.dto.AccountView;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-through Redis cache for account lookups.
 *
 * <p>Kept in a separate bean because Spring's caching is proxy based, so a
 * {@code @Cacheable} method called from a sibling method on the same object
 * would silently bypass the cache.
 *
 * <p>Callers evict <em>after</em> their transaction commits. Evicting earlier
 * would let a concurrent reader repopulate the cache from the pre-commit
 * snapshot and leave a stale balance behind indefinitely.
 */
@Component
public class CachedAccountReader {

    private final AccountRepository accounts;

    public CachedAccountReader(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Cacheable(cacheNames = RedisConfig.ACCOUNTS_CACHE, key = "#accountNumber")
    @Transactional(readOnly = true)
    public AccountView load(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber)
                .map(AccountView::from)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));
    }

    @CacheEvict(cacheNames = RedisConfig.ACCOUNTS_CACHE, key = "#accountNumber")
    public void evict(String accountNumber) {
        // Annotation-driven; nothing to do here.
    }
}
