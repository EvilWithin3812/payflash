package com.payflash.auth;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes "log out everywhere", password resets and account deletion take effect immediately.
 *
 * <p>Refresh tokens are checked against {@code users.token_version} in the database. Access tokens are
 * checked on every request without a database query: when a user's version is bumped, the new version is
 * written to Redis for one access-token lifetime, and any access token carrying an older version is
 * rejected. After that lifetime every old access token has expired anyway, so the key can disappear.
 *
 * <p>If Redis is unreachable the check is skipped (fail open), like rate limiting: an outage must not log
 * everyone out. The exposure is bounded by the 15-minute access-token lifetime.
 */
@Component
public class TokenRevocations {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocations.class);
    private static final String PREFIX = "auth:tv:";

    private final StringRedisTemplate redis;
    private final Duration keep;

    public TokenRevocations(StringRedisTemplate redis, JwtProperties props) {
        this.redis = redis;
        this.keep = props.accessTokenTtl().plusMinutes(1);
    }

    public void revokeOlderThan(Long userId, int currentVersion) {
        try {
            redis.opsForValue().set(PREFIX + userId, Integer.toString(currentVersion), keep);
        } catch (RuntimeException e) {
            log.warn("Could not record token revocation for user {} in Redis", userId, e);
        }
    }

    public boolean isRevoked(AuthUser user) {
        try {
            String current = redis.opsForValue().get(PREFIX + user.id());
            return current != null && user.tokenVersion() < Integer.parseInt(current);
        } catch (RuntimeException e) {
            log.debug("Token revocation check skipped: Redis unavailable", e);
            return false;
        }
    }
}
