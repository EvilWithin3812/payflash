package com.payflash.ratelimit;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimiterTest {

    @Test
    @SuppressWarnings("unchecked")
    void failsOpenWhenRedisIsUnavailable() {
        ProxyManager<String> broken = mock(ProxyManager.class);
        when(broken.builder()).thenThrow(new IllegalStateException("Connection refused"));

        var decision = new RateLimiter(broken).tryConsume("rl:api:user:1", 10);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(-1);
    }
}
