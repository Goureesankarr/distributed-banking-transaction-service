package com.bank.txn.error;

import org.springframework.http.HttpStatus;

public class ValidationException extends BankingException {

    public ValidationException(String message) {
        super("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, message);
    }
}
