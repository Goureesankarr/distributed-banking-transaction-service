package com.bank.txn.error;

import org.springframework.http.HttpStatus;

/** A concurrent request holding the same idempotency key has not finished yet. */
public class RequestInProgressException extends BankingException {

    public RequestInProgressException(String key) {
        super("REQUEST_IN_PROGRESS", HttpStatus.CONFLICT,
                "A request with Idempotency-Key '%s' is still being processed".formatted(key));
    }
}
