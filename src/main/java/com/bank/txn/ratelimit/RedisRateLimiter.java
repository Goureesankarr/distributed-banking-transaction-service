package com.bank.txn.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Distributed token bucket backed by a single atomic Redis script, so every
 * instance of the service enforces one shared limit per principal.
 *
 * <p>If Redis is unreachable the limiter fails open: rejecting live banking
 * traffic because a cache node is down would turn a degraded dependency into
 * an outage. The fail-open path is counted so it is visible in Grafana.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final long BUCKET_TTL_MILLIS = 300_000L;

    private final StringRedisTemplate redis;
    private final RedisScript<List> tokenBucketScript;
    private final Counter throttled;
    private final Counter failedOpen;

    public RedisRateLimiter(StringRedisTemplate redis,
                            RedisScript<List> tokenBucketScript,
                            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.tokenBucketScript = tokenBucketScript;
        this.throttled = Counter.builder("banking.ratelimit.throttled")
                .description("Requests rejected with HTTP 429")
                .register(meterRegistry);
        this.failedOpen = Counter.builder("banking.ratelimit.failed_open")
                .description("Requests allowed through because Redis was unavailable")
                .register(meterRegistry);
    }

    public RateLimitDecision tryConsume(String bucketKey, int capacity, int refillPerMinute) {
        double refillPerSecond = refillPerMinute / 60.0;
        try {
            List<?> result = redis.execute(
                    tokenBucketScript,
                    List.of("ratelimit:" + bucketKey),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSecond),
                    String.valueOf(System.currentTimeMillis()),
                    "1",
                    String.valueOf(BUCKET_TTL_MILLIS));

            if (result == null || result.size() < 3) {
                return RateLimitDecision.allowed(capacity, capacity);
            }

            boolean allowed = toLong(result.get(0)) == 1L;
            long remaining = toLong(result.get(1));
            long retryAfter = toLong(result.get(2));
            if (!allowed) {
                throttled.increment();
            }
            return new RateLimitDecision(allowed, remaining, retryAfter, capacity);
        } catch (DataAccessException e) {
            failedOpen.increment();
            log.warn("Rate limiter unavailable, allowing request for bucket {}: {}", bucketKey, e.getMessage());
            return RateLimitDecision.allowed(capacity, capacity);
        }
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }
}
