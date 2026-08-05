package com.bank.txn.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code,
                       String message,
                       String path,
                       Instant timestamp,
                       Map<String, String> fieldErrors) {

    public static ApiError of(String code, String message, String path) {
        return new ApiError(code, message, path, Instant.now(), null);
    }
}
