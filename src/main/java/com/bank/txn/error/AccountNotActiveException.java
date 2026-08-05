package com.bank.txn.error;

import com.bank.txn.domain.AccountStatus;
import org.springframework.http.HttpStatus;

public class AccountNotActiveException extends BankingException {

    public AccountNotActiveException(String accountNumber, AccountStatus status) {
        super("ACCOUNT_NOT_ACTIVE", HttpStatus.UNPROCESSABLE_ENTITY,
                "Account %s is %s and cannot take part in a transfer".formatted(accountNumber, status));
    }
}
