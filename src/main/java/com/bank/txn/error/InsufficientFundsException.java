package com.bank.txn.error;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

public class InsufficientFundsException extends BankingException {

    public InsufficientFundsException(String accountNumber, BigDecimal balance, BigDecimal requested) {
        super("INSUFFICIENT_FUNDS", HttpStatus.UNPROCESSABLE_ENTITY,
                "Account %s has %s available but %s was requested".formatted(accountNumber, balance, requested));
    }
}
