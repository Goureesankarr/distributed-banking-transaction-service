package com.bank.txn.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransferRequest(
        @NotBlank String sourceAccountNumber,

        @NotBlank String targetAccountNumber,

        @NotNull
        @DecimalMin(value = "0.0001", message = "must be greater than zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @NotNull
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO-4217 code")
        String currency,

        @Size(max = 255) String description) {
}
