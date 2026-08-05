package com.bank.txn.error;

import org.springframework.http.HttpStatus;

public class CurrencyMismatchException extends BankingException {

    public CurrencyMismatchException(String accountNumber, String accountCurrency, String transferCurrency) {
        super("CURRENCY_MISMATCH", HttpStatus.UNPROCESSABLE_ENTITY,
                "Account %s is denominated in %s but the transfer is in %s"
                        .formatted(accountNumber, accountCurrency, transferCurrency));
    }
}
