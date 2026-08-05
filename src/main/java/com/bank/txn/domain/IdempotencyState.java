package com.bank.txn.domain;

public enum IdempotencyState {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
