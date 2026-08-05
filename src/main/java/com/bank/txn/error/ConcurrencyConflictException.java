package com.bank.txn.error;

import org.springframework.http.HttpStatus;

/** Optimistic locking retries were exhausted; the caller should try again. */
public class ConcurrencyConflictException extends BankingException {

    public ConcurrencyConflictException(int attempts) {
        super("CONCURRENCY_CONFLICT", HttpStatus.CONFLICT,
                "The account was modified concurrently and the transfer could not be applied after %d attempts"
                        .formatted(attempts));
    }
}
