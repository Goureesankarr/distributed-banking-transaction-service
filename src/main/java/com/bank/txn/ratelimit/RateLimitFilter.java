package com.bank.txn.ratelimit;

import com.bank.txn.config.BankingProperties;
import com.bank.txn.web.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-principal throttling. Runs after JWT authentication so the bucket is
 * keyed by username where possible and falls back to the client IP for
 * anonymous traffic.
 *
 * <p>Money movement gets its own, tighter bucket than read traffic: a client
 * hammering transfer creation is a very different risk from one polling
 * balances.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisRateLimiter limiter;
    private final BankingProperties.RateLimit config;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RedisRateLimiter limiter,
                           BankingProperties.RateLimit config,
                           ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!config.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        boolean moneyMovement = isMoneyMovement(request);
        int capacity = moneyMovement ? config.getTransferCapacity() : config.getDefaultCapacity();
        int refill = moneyMovement ? config.getTransferRefillPerMinute() : config.getDefaultRefillPerMinute();
        String bucket = (moneyMovement ? "transfer:" : "api:") + principal(request);

        RateLimitDecision decision = limiter.tryConsume(bucket, capacity, refill);

        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, decision.remaining())));

        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                "RATE_LIMIT_EXCEEDED",
                "Too many requests. Retry in %d second(s).".formatted(decision.retryAfterSeconds()),
                request.getRequestURI()));
    }

    private static boolean isMoneyMovement(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/transfers");
    }

    private static String principal(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return "ip:" + clientIp(request);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
