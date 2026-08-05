package com.bank.txn.error;

import org.springframework.http.HttpStatus;

public class AccessDeniedForAccountException extends BankingException {

    public AccessDeniedForAccountException(String accountNumber) {
        super("ACCOUNT_FORBIDDEN", HttpStatus.FORBIDDEN,
                "You are not authorised to operate on account %s".formatted(accountNumber));
    }
}
