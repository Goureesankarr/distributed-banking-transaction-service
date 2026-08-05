package com.bank.txn.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank
            @Size(min = 3, max = 64)
            @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "may only contain letters, digits, dot, underscore or hyphen")
            String username,

            @NotBlank
            @Size(min = 10, max = 100, message = "must be between 10 and 100 characters")
            String password,

            @NotBlank
            @Size(max = 160)
            String fullName) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }

    public record AuthResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String username,
            List<String> roles) {

        public static AuthResponse bearer(String token, long ttl, String username, List<String> roles) {
            return new AuthResponse(token, "Bearer", ttl, username, roles);
        }
    }
}
