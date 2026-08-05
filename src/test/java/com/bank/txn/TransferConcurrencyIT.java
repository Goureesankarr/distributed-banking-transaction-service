package com.bank.txn;

import com.bank.txn.domain.Account;
import com.bank.txn.domain.TransferStatus;
import com.bank.txn.error.ConcurrencyConflictException;
import com.bank.txn.repository.AccountRepository;
import com.bank.txn.repository.LedgerEntryRepository;
import com.bank.txn.repository.TransferRepository;
import com.bank.txn.service.TransferService;
import com.bank.txn.web.dto.TransferRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many writers, one hot account, no lost updates.
 */
@Tag("integration")
class TransferConcurrencyIT extends AbstractIntegrationTest {

    private static final int WRITERS = 20;
    private static final BigDecimal AMOUNT = new BigDecimal("10.0000");
    private static final int CLIENT_RETRIES = 10;

    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accounts;
    @Autowired private TransferRepository transfers;
    @Autowired private LedgerEntryRepository ledger;

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    @DisplayName("20 concurrent transfers from one account all settle, with exact balances")
    void concurrentTransfersDoNotLoseUpdates() throws Exception {
        String token = registerAndGetToken("payer");
        String source = openAccount(token, "USD", "1000.0000");
        String target = openAccount(token, "USD", "0.0000");

        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger settled = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            // Submit first, then release the latch, so every writer is parked
            // and ready and they all hit the account at once.
            List<Future<Boolean>> results = java.util.stream.IntStream.range(0, WRITERS)
                    .mapToObj(i -> pool.submit((Callable<Boolean>) () -> {
                        startGun.await();
                        boolean ok = sendWithClientRetries("payer", source, target);
                        if (ok) {
                            settled.incrementAndGet();
                        }
                        return ok;
                    }))
                    .toList();

            startGun.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get()).as("every writer eventually settles").isTrue();
            }
        }

        Account sourceAccount = accounts.findByAccountNumber(source).orElseThrow();
        Account targetAccount = accounts.findByAccountNumber(target).orElseThrow();

        BigDecimal moved = AMOUNT.multiply(BigDecimal.valueOf(settled.get()));

        assertThat(settled.get()).isEqualTo(WRITERS);
        assertThat(sourceAccount.getBalance())
                .as("no update was silently overwritten")
                .isEqualByComparingTo(new BigDecimal("1000.0000").subtract(moved));
        assertThat(targetAccount.getBalance()).isEqualByComparingTo(moved);

        // Conservation of money, checked two independent ways.
        assertThat(sourceAccount.getBalance().add(targetAccount.getBalance()))
                .isEqualByComparingTo("1000.0000");
        assertThat(ledger.netPostedAmount()).isEqualByComparingTo("0");

        long completed = transfers.findAll().stream()
                .filter(t -> t.getStatus() == TransferStatus.COMPLETED)
                .count();
        assertThat(completed).isEqualTo(WRITERS);
        assertThat(ledger.findAll()).hasSize(WRITERS * 2);
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    @DisplayName("concurrent transfers cannot overdraw the account between them")
    void concurrentTransfersCannotOverdraw() throws Exception {
        String token = registerAndGetToken("thin-margin");
        // Only three of the ten writers can be funded.
        String source = openAccount(token, "USD", "30.0000");
        String target = openAccount(token, "USD", "0.0000");

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<? extends Future<?>> results = java.util.stream.IntStream.range(0, 10)
                    .mapToObj(i -> pool.submit(() -> {
                        int status = attempt("thin-margin", source, target);
                        if (status == 201) {
                            succeeded.incrementAndGet();
                        } else if (status == 422) {
                            rejected.incrementAndGet();
                        }
                    }))
                    .toList();
            for (Future<?> result : results) {
                result.get();
            }
        }

        Account sourceAccount = accounts.findByAccountNumber(source).orElseThrow();

        assertThat(succeeded.get()).isEqualTo(3);
        assertThat(sourceAccount.getBalance())
                .as("the balance floor held under contention")
                .isEqualByComparingTo("0");
        assertThat(sourceAccount.getBalance().signum()).isNotNegative();
        assertThat(ledger.netPostedAmount()).isEqualByComparingTo("0");
    }

    /**
     * Mimics a well-behaved client: on 409 (lock contention) retry the same
     * request with the same idempotency key. Exactly one transfer must result.
     */
    private boolean sendWithClientRetries(String user, String source, String target) {
        String idempotencyKey = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < CLIENT_RETRIES; attempt++) {
            try {
                return transferService.transfer(user, idempotencyKey, false,
                        new TransferRequest(source, target, AMOUNT, "USD", "load test")).status() == 201;
            } catch (ConcurrencyConflictException e) {
                Thread.onSpinWait();
            }
        }
        return false;
    }

    private int attempt(String user, String source, String target) {
        for (int i = 0; i < CLIENT_RETRIES; i++) {
            try {
                return transferService.transfer(user, UUID.randomUUID().toString(), false,
                        new TransferRequest(source, target, AMOUNT, "USD", "overdraw probe")).status();
            } catch (ConcurrencyConflictException e) {
                Thread.onSpinWait();
            }
        }
        return 409;
    }
}
