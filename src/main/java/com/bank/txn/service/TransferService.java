package com.bank.txn.service;

import com.bank.txn.domain.Account;
import com.bank.txn.domain.AuditOutcome;
import com.bank.txn.domain.Transfer;
import com.bank.txn.error.AccessDeniedForAccountException;
import com.bank.txn.error.BankingException;
import com.bank.txn.error.ConcurrencyConflictException;
import com.bank.txn.error.ValidationException;
import com.bank.txn.repository.TransferRepository;
import com.bank.txn.web.dto.ApiError;
import com.bank.txn.web.dto.TransferRequest;
import com.bank.txn.web.dto.TransferView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates a transfer: idempotency claim, optimistic-lock retry, audit,
 * metrics, cache invalidation.
 *
 * <p>Not transactional. It owns the retry loop, and each
 * attempt must run in a transaction of its own. See {@link TransferExecutor}.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private static final String ENDPOINT = "POST /api/v1/transfers";
    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MILLIS = 20L;
    private static final long MAX_BACKOFF_MILLIS = 250L;

    /** Open-ended history bounds, kept well inside what a timestamptz can hold. */
    private static final Instant EARLIEST = Instant.EPOCH;
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private final TransferExecutor executor;
    private final TransferRepository transfers;
    private final AccountService accountService;
    private final IdempotencyService idempotency;
    private final CachedAccountReader cache;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Timer transferTimer;
    private final Counter completed;
    private final Counter retries;
    private final Counter replays;

    public TransferService(TransferExecutor executor,
                           TransferRepository transfers,
                           AccountService accountService,
                           IdempotencyService idempotency,
                           CachedAccountReader cache,
                           AuditService audit,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry) {
        this.executor = executor;
        this.transfers = transfers;
        this.accountService = accountService;
        this.idempotency = idempotency;
        this.cache = cache;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;

        this.transferTimer = Timer.builder("banking.transfer.duration")
                .description("End-to-end latency of a transfer request")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.completed = Counter.builder("banking.transfer.completed")
                .description("Transfers that moved money")
                .register(meterRegistry);
        this.retries = Counter.builder("banking.transfer.optimistic_retries")
                .description("Attempts abandoned because another transaction won the version race")
                .register(meterRegistry);
        this.replays = Counter.builder("banking.transfer.idempotent_replays")
                .description("Requests answered from a stored idempotent response")
                .register(meterRegistry);
    }

    /** Status and body of the response, whether freshly computed or replayed. */
    public record TransferOutcome(int status, String body, boolean replayed) {
    }

    public TransferOutcome transfer(String username, String idempotencyKey, boolean admin, TransferRequest request) {
        validate(idempotencyKey, request);

        String requestHash = IdempotencyService.hash(canonicalise(request));
        Optional<IdempotencyService.StoredResponse> stored =
                idempotency.begin(idempotencyKey, username, ENDPOINT, requestHash);
        if (stored.isPresent()) {
            replays.increment();
            log.info("Replaying stored response for Idempotency-Key {} (user {})", idempotencyKey, username);
            return new TransferOutcome(stored.get().status(), stored.get().body(), true);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TransferOutcome outcome = process(username, idempotencyKey, admin, request);
            idempotency.complete(idempotencyKey, username, outcome.status(), outcome.body());
            return outcome;
        } catch (RuntimeException e) {
            // Transient (lock contention) or unexpected: let the caller retry
            // with the same key rather than pinning it to a failure the
            // request did not really cause.
            idempotency.release(idempotencyKey, username);
            throw e;
        } finally {
            sample.stop(transferTimer);
        }
    }

    private TransferOutcome process(String username, String idempotencyKey, boolean admin, TransferRequest request) {
        TransferCommand command = new TransferCommand(
                newReference(),
                request.sourceAccountNumber(),
                request.targetAccountNumber(),
                request.amount(),
                request.currency().toUpperCase(Locale.ROOT),
                request.description(),
                idempotencyKey,
                username,
                accountService.requireUser(username).getId(),
                admin);

        try {
            Transfer transfer = executeWithRetry(command);
            evictCaches(command);
            completed.increment();
            audit.record(username, "TRANSFER_COMPLETED", "Transfer", transfer.getReference(),
                    AuditOutcome.SUCCESS, auditDetails(command, null));
            return new TransferOutcome(
                    HttpStatus.CREATED.value(), toJson(TransferView.from(transfer)), false);
        } catch (ConcurrencyConflictException e) {
            audit.record(username, "TRANSFER_CONTENTION", "Transfer", command.reference(),
                    AuditOutcome.FAILURE, auditDetails(command, e.getMessage()));
            throw e;
        } catch (BankingException e) {
            // A deterministic business rejection: record it, bind it to the
            // idempotency key, and answer the same way on every retry.
            //
            // Only genuine money-movement rejections (insufficient funds,
            // frozen account, currency mismatch) get a FAILED transfer row.
            // An authorisation failure must not write a row into the history
            // of an account the caller has no business touching.
            Optional<Transfer> failed = e.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY
                    ? executor.recordFailure(command, e.getMessage())
                    : Optional.empty();
            meterRegistry.counter("banking.transfer.failed", "reason", e.getCode()).increment();
            audit.record(username, "TRANSFER_REJECTED", "Transfer",
                    failed.map(Transfer::getReference).orElse(command.reference()),
                    AuditOutcome.FAILURE, auditDetails(command, e.getMessage()));
            return new TransferOutcome(
                    e.getStatus().value(),
                    toJson(new ApiError(e.getCode(), e.getMessage(), ENDPOINT, Instant.now(), null)),
                    false);
        }
    }

    /**
     * Optimistic locking in practice: no row is locked while the transfer is
     * computed, so an unlucky attempt simply loses the version race and is
     * replayed against fresh state. Backoff is randomised so a burst of
     * contending writers on one hot account does not retry in lockstep.
     */
    private Transfer executeWithRetry(TransferCommand command) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return executor.execute(command);
            } catch (OptimisticLockingFailureException | CannotAcquireLockException e) {
                retries.increment();
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Giving up on transfer {} after {} contended attempts",
                            command.reference(), MAX_ATTEMPTS);
                    throw new ConcurrencyConflictException(MAX_ATTEMPTS);
                }
                backoff(attempt);
            }
        }
        throw new ConcurrencyConflictException(MAX_ATTEMPTS);
    }

    private static void backoff(int attempt) {
        long ceiling = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS << (attempt - 1));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, ceiling + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a contended transfer", ie);
        }
    }

    @Transactional(readOnly = true)
    public Page<Transfer> history(String username,
                                  String accountNumber,
                                  Instant from,
                                  Instant to,
                                  boolean admin,
                                  Pageable pageable) {
        Account account = accountService.requireAccount(accountNumber);
        if (!admin && !account.getOwnerId().equals(accountService.requireUser(username).getId())) {
            throw new AccessDeniedForAccountException(accountNumber);
        }
        return transfers.findHistory(
                account.getId(),
                from == null ? EARLIEST : from,
                to == null ? LATEST : to,
                pageable);
    }

    @Transactional(readOnly = true)
    public Optional<Transfer> findByReference(String reference) {
        return transfers.findByReference(reference);
    }

    private void evictCaches(TransferCommand command) {
        cache.evict(command.sourceAccountNumber());
        cache.evict(command.targetAccountNumber());
    }

    private void validate(String idempotencyKey, TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("The Idempotency-Key header is required for transfers");
        }
        if (idempotencyKey.length() > 120) {
            throw new ValidationException("Idempotency-Key must be at most 120 characters");
        }
        if (request.sourceAccountNumber().equals(request.targetAccountNumber())) {
            throw new ValidationException("Source and target accounts must differ");
        }
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Amount must be greater than zero");
        }
    }

    /**
     * Stable rendering of the request, so that reusing a key with a different
     * payload is detected instead of silently replaying the wrong response.
     */
    private static String canonicalise(TransferRequest request) {
        return String.join("|",
                request.sourceAccountNumber(),
                request.targetAccountNumber(),
                request.amount().stripTrailingZeros().toPlainString(),
                request.currency().toUpperCase(Locale.ROOT),
                request.description() == null ? "" : request.description());
    }

    private static String newReference() {
        return "TRF-" + UUID.randomUUID().toString().replace("-", "");
    }

    private Map<String, String> auditDetails(TransferCommand command, String reason) {
        Map<String, String> details = new java.util.LinkedHashMap<>();
        details.put("source", command.sourceAccountNumber());
        details.put("target", command.targetAccountNumber());
        details.put("amount", command.amount().toPlainString());
        details.put("currency", command.currency());
        details.put("idempotencyKey", command.idempotencyKey());
        if (reason != null) {
            details.put("reason", reason);
        }
        return details;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise response body", e);
        }
    }
}
