package com.bank.txn.service;

import java.math.BigDecimal;
import java.util.UUID;

/** Everything {@link TransferExecutor} needs, resolved once before retrying. */
public record TransferCommand(String reference,
                              String sourceAccountNumber,
                              String targetAccountNumber,
                              BigDecimal amount,
                              String currency,
                              String description,
                              String idempotencyKey,
                              String initiatedBy,
                              UUID initiatorId,
                              boolean admin) {
}
