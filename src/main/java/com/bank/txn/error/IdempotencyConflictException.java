package com.bank.txn.error;

import org.springframework.http.HttpStatus;

/** The key has been seen before, but with a different request body. */
public class IdempotencyConflictException extends BankingException {

    public IdempotencyConflictException(String key) {
        super("IDEMPOTENCY_KEY_REUSED", HttpStatus.UNPROCESSABLE_ENTITY,
                "Idempotency-Key '%s' was already used for a different request payload".formatted(key));
    }
}
