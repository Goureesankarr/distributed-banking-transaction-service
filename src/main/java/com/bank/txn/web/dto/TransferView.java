package com.bank.txn.web.dto;

import com.bank.txn.domain.Transfer;
import com.bank.txn.domain.TransferStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferView(UUID id,
                           String reference,
                           UUID sourceAccountId,
                           UUID targetAccountId,
                           BigDecimal amount,
                           String currency,
                           TransferStatus status,
                           String description,
                           String failureReason,
                           String initiatedBy,
                           Instant createdAt,
                           Instant completedAt) {

    public static TransferView from(Transfer transfer) {
        return new TransferView(
                transfer.getId(),
                transfer.getReference(),
                transfer.getSourceAccountId(),
                transfer.getTargetAccountId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus(),
                transfer.getDescription(),
                transfer.getFailureReason(),
                transfer.getInitiatedBy(),
                transfer.getCreatedAt(),
                transfer.getCompletedAt());
    }
}
