package com.bank.txn.ratelimit;

public record RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds, int limit) {

    public static RateLimitDecision allowed(long remaining, int limit) {
        return new RateLimitDecision(true, remaining, 0, limit);
    }
}
