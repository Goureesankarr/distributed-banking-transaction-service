package com.bank.txn.error;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BankingException {

    public ResourceNotFoundException(String resource, Object identifier) {
        super("NOT_FOUND", HttpStatus.NOT_FOUND, "%s %s was not found".formatted(resource, identifier));
    }
}
