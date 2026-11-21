package com.payflash.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token-bucket rate limiter backed by Redis (Bucket4j, compare-and-swap via Lua).
 * Each key gets {@code perMinute} tokens that refill continuously ("greedy" refill), so a client
 * that pauses briefly gets a few requests back instead of waiting for a fixed window to reset.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final ProxyManager<String> buckets;

    public RateLimiter(ProxyManager<String> buckets) {
        this.buckets = buckets;
    }

    public Decision tryConsume(String key, int perMinute) {
        try {
            BucketConfiguration config = BucketConfiguration.builder()
                    .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                    .build();
            ConsumptionProbe probe = buckets.builder().build(key, () -> config).tryConsumeAndReturnRemaining(1);
            long retryAfterSeconds = probe.isConsumed() ? 0
                    : Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
            return new Decision(probe.isConsumed(), perMinute, probe.getRemainingTokens(), retryAfterSeconds);
        } catch (RuntimeException e) {
            // Fail open: if Redis is down we would rather serve traffic than reject every request.
            // Money safety does not depend on this limiter (locks + idempotency keys handle that).
            log.warn("Rate limiter unavailable, allowing request for {}: {}", key, e.toString());
            return new Decision(true, perMinute, -1, 0);
        }
    }

    /** remaining = -1 means the limiter could not be consulted. */
    public record Decision(boolean allowed, int limit, long remaining, long retryAfterSeconds) {
    }
}
