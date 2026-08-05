package com.bank.txn.domain;

import com.bank.txn.error.AccountNotActiveException;
import com.bank.txn.error.CurrencyMismatchException;
import com.bank.txn.error.InsufficientFundsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static Account account(String balance) {
        return new Account("ACC0000000000001", UUID.randomUUID(), "USD", new BigDecimal(balance));
    }

    @Test
    @DisplayName("debit reduces the balance")
    void debitReducesBalance() {
        Account account = account("100.0000");

        account.debit(new BigDecimal("30.0000"), "USD");

        assertThat(account.getBalance()).isEqualByComparingTo("70.0000");
    }

    @Test
    @DisplayName("a debit larger than the balance is refused and leaves the balance untouched")
    void debitCannotOverdraw() {
        Account account = account("50.0000");

        assertThatThrownBy(() -> account.debit(new BigDecimal("50.0001"), "USD"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("50.0000");
    }

    @Test
    @DisplayName("spending the exact balance to zero is allowed")
    void debitToZeroIsAllowed() {
        Account account = account("25.5000");

        account.debit(new BigDecimal("25.5000"), "USD");

        assertThat(account.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void creditIncreasesBalance() {
        Account account = account("10.0000");

        account.credit(new BigDecimal("0.0001"), "USD");

        assertThat(account.getBalance()).isEqualByComparingTo("10.0001");
    }

    @Test
    @DisplayName("a frozen account can neither send nor receive")
    void frozenAccountRejectsBothDirections() {
        Account account = account("100.0000");
        account.setStatus(AccountStatus.FROZEN);

        assertThatThrownBy(() -> account.debit(BigDecimal.ONE, "USD"))
                .isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> account.credit(BigDecimal.ONE, "USD"))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void currencyMustMatch() {
        Account account = account("100.0000");

        assertThatThrownBy(() -> account.debit(BigDecimal.ONE, "EUR"))
                .isInstanceOf(CurrencyMismatchException.class);
    }
}
