package com.bank.txn.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotNull
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO-4217 code")
        String currency,

        @NotNull
        @DecimalMin(value = "0.00", message = "cannot be negative")
        @Digits(integer = 15, fraction = 4)
        BigDecimal openingBalance) {
}
