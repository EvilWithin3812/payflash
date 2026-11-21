package com.payflash.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    /** Reuses Spring's Redis connection details (spring.data.redis.* or a Testcontainers service connection). */
    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient(RedisConnectionDetails details) {
        var standalone = details.getStandalone();
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(standalone.getHost())
                .withPort(standalone.getPort())
                .withDatabase(standalone.getDatabase())
                .withTimeout(Duration.ofMillis(500));
        if (details.getPassword() != null) {
            if (details.getUsername() != null) {
                uri.withAuthentication(details.getUsername(), details.getPassword());
            } else {
                uri.withPassword(details.getPassword().toCharArray());
            }
        }
        return RedisClient.create(uri.build());
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    RateLimiter rateLimiter(StatefulRedisConnection<String, byte[]> connection) {
        var proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                // Drop idle buckets from Redis once they would be full again anyway.
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                        Duration.ofSeconds(10)))
                .requestTimeout(Duration.ofMillis(500))
                .build();
        return new RateLimiter(proxyManager);
    }

    @Bean
    RateLimitFilter rateLimitFilter(RateLimiter limiter, RateLimitProperties props, ObjectMapper mapper,
                                    MeterRegistry metrics) {
        return new RateLimitFilter(limiter, props, mapper, metrics);
    }

    /** Spring Boot auto-registers every Filter bean with the servlet container; we only want it in the security chain. */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
