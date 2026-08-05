package com.bank.txn.error;

import org.springframework.http.HttpStatus;

/**
 * Base type for expected, client-visible failures. Anything extending this is
 * safe to surface verbatim; everything else is masked as a 500 by the
 * {@link com.bank.txn.web.GlobalExceptionHandler}.
 */
public abstract class BankingException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    protected BankingException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
