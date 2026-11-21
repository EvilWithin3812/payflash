package com.payflash.auth;

import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Locks an account for a while after too many wrong passwords. The per-IP rate limit can't stop someone
 * guessing one account's password from thousands of IPs; this can. While locked, even the right password
 * is refused; resetting the password (which proves inbox ownership) clears the lock.
 *
 * <p>Keyed by the email as typed (normalised), so it also works for addresses with no account.
 * Fails open if Redis is down, like the rate limiter.
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);
    private static final String PREFIX = "auth:failed:";

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration lock;

    public LoginThrottle(StringRedisTemplate redis,
                         @Value("${app.auth.max-failed-logins:5}") int maxFailures,
                         @Value("${app.auth.login-lock:15m}") Duration lock) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.lock = lock;
    }

    public boolean isLocked(String email) {
        try {
            String count = redis.opsForValue().get(key(email));
            return count != null && Integer.parseInt(count) >= maxFailures;
        } catch (RuntimeException e) {
            log.debug("Login throttle check skipped: Redis unavailable", e);
            return false;
        }
    }

    public void recordFailure(String email) {
        try {
            Long count = redis.opsForValue().increment(key(email));
            if (count != null && count == 1) {
                redis.expire(key(email), lock);
            }
        } catch (RuntimeException e) {
            log.debug("Login failure not recorded: Redis unavailable", e);
        }
    }

    public void reset(String email) {
        try {
            redis.delete(key(email));
        } catch (RuntimeException e) {
            log.debug("Login throttle reset skipped: Redis unavailable", e);
        }
    }

    private static String key(String email) {
        return PREFIX + email.trim().toLowerCase(Locale.ROOT);
    }
}
