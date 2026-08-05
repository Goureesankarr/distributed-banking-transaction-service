package com.bank.txn.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Claim ticket for an {@code Idempotency-Key}. The row is inserted before the
 * work starts and completed afterwards with the exact response that was sent,
 * so a retried request replays the original outcome instead of moving money
 * twice.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 160)
    private String endpoint;

    /** SHA-256 of the canonical request body, to detect key reuse with different payloads. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyState state = IdempotencyState.IN_PROGRESS;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey,
                             String username,
                             String endpoint,
                             String requestHash,
                             Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.username = username;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.expiresAt = expiresAt;
    }

    public void complete(int status, String body) {
        this.state = IdempotencyState.COMPLETED;
        this.responseStatus = status;
        this.responseBody = body;
    }

    public void fail(int status, String body) {
        this.state = IdempotencyState.FAILED;
        this.responseStatus = status;
        this.responseBody = body;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getUsername() {
        return username;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyState getState() {
        return state;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
