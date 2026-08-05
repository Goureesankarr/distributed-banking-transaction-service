package com.bank.txn.service;

import com.bank.txn.config.BankingProperties;
import com.bank.txn.domain.IdempotencyRecord;
import com.bank.txn.domain.IdempotencyState;
import com.bank.txn.error.IdempotencyConflictException;
import com.bank.txn.error.RequestInProgressException;
import com.bank.txn.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Makes unsafe endpoints replay-safe.
 *
 * <p>The concurrency primitive is the {@code (idempotency_key, username)}
 * unique index. Two simultaneous retries both try to INSERT a claim row;
 * Postgres lets exactly one through and the loser gets 409 rather than a
 * second transfer. When the winner finishes it stores the response it sent,
 * so any later retry of the same key replays that response byte for byte.
 *
 * <p>Every method runs in its own transaction: the claim must be visible to
 * other requests immediately, and must survive the rollback of the business
 * transaction it guards.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository records;
    private final BankingProperties properties;

    public IdempotencyService(IdempotencyRecordRepository records, BankingProperties properties) {
        this.records = records;
        this.properties = properties;
    }

    public record StoredResponse(int status, String body) {
    }

    /**
     * Must be called <em>outside</em> an active transaction: the claim insert
     * has to commit on its own so competing requests can see it, and its
     * failure must not poison a surrounding transaction.
     *
     * @return the previously stored response when this key has already been
     *         processed, or empty when the caller now owns the key and should
     *         do the work.
     */
    public Optional<StoredResponse> begin(String key, String username, String endpoint, String requestHash) {
        Optional<IdempotencyRecord> existing = find(key, username);
        if (existing.isPresent()) {
            return evaluate(existing.get(), key, requestHash);
        }
        try {
            claim(key, username, endpoint, requestHash);
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            // Lost the insert race: another request owns the key right now.
            IdempotencyRecord winner = find(key, username)
                    .orElseThrow(() -> new RequestInProgressException(key));
            return evaluate(winner, key, requestHash);
        }
    }

    // Not annotated: these are called from begin() on the same
    // instance, so a @Transactional annotation here would be bypassed by
    // self-invocation. Each repository call opens and commits its own
    // transaction, which is exactly the isolation the claim protocol needs.
    Optional<IdempotencyRecord> find(String key, String username) {
        return records.findByIdempotencyKeyAndUsername(key, username);
    }

    void claim(String key, String username, String endpoint, String requestHash) {
        records.saveAndFlush(new IdempotencyRecord(
                key, username, endpoint, requestHash,
                Instant.now().plus(properties.getIdempotency().getTtl())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, String username, int status, String body) {
        records.findByIdempotencyKeyAndUsername(key, username)
                .ifPresent(record -> record.complete(status, body));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String key, String username, int status, String body) {
        records.findByIdempotencyKeyAndUsername(key, username)
                .ifPresent(record -> record.fail(status, body));
    }

    /**
     * Drops the claim so the client can retry. Used for transient failures
     * (lock contention, infrastructure errors) where pinning the key to an
     * error response would be wrong, since the same request may well succeed next
     * time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key, String username) {
        records.findByIdempotencyKeyAndUsername(key, username)
                .filter(record -> record.getState() == IdempotencyState.IN_PROGRESS)
                .ifPresent(records::delete);
    }

    private Optional<StoredResponse> evaluate(IdempotencyRecord record, String key, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(key);
        }
        if (record.getState() == IdempotencyState.IN_PROGRESS) {
            throw new RequestInProgressException(key);
        }
        return Optional.of(new StoredResponse(
                record.getResponseStatus() == null ? 200 : record.getResponseStatus(),
                record.getResponseBody()));
    }

    /** Reclaims storage from keys whose replay window has passed. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    @Transactional
    public void purgeExpired() {
        int removed = records.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired idempotency records", removed);
        }
    }

    public static String hash(String canonicalRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
