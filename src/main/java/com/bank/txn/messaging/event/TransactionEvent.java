package com.bank.txn.messaging.event;

import com.bank.txn.domain.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published to {@code banking.transactions.v1}.
 *
 * <p>Keyed by source account on the wire so that all events for one account
 * land on the same partition and are therefore consumed in order.
 */
public record TransactionEvent(UUID eventId,
                               String eventType,
                               UUID transferId,
                               String reference,
                               UUID sourceAccountId,
                               UUID targetAccountId,
                               BigDecimal amount,
                               String currency,
                               String status,
                               String failureReason,
                               String initiatedBy,
                               Instant occurredAt) {

    public static final String TRANSFER_COMPLETED = "TRANSFER_COMPLETED";
    public static final String TRANSFER_FAILED = "TRANSFER_FAILED";

    public static TransactionEvent from(Transfer transfer) {
        String type = switch (transfer.getStatus()) {
            case COMPLETED -> TRANSFER_COMPLETED;
            case FAILED -> TRANSFER_FAILED;
            case PENDING -> "TRANSFER_PENDING";
        };
        return new TransactionEvent(
                UUID.randomUUID(),
                type,
                transfer.getId(),
                transfer.getReference(),
                transfer.getSourceAccountId(),
                transfer.getTargetAccountId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus().name(),
                transfer.getFailureReason(),
                transfer.getInitiatedBy(),
                Instant.now());
    }
}
