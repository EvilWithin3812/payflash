package com.payflash.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Requests allowed per minute for each policy. Buckets live in Redis, so the limits hold across
 * every app instance (a per-JVM limiter would multiply the limit by the number of instances).
 *
 * @param enabled  master switch
 * @param auth     signup / login / refresh, keyed by client IP (slows down credential stuffing)
 * @param money    top-ups, transfers, payment orders and sale reserve/pay, keyed by user id
 * @param api      every other API call, keyed by user id (or IP when anonymous)
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(boolean enabled, int auth, int money, int api) {

    public RateLimitProperties {
        if (auth <= 0) auth = 10;
        if (money <= 0) money = 20;
        if (api <= 0) api = 120;
    }
}
