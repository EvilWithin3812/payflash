package com.payflash.auth;

import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "ZGV2LW9ubHktc2VjcmV0LWNoYW5nZS1tZS1wbGVhc2UtMzItYnl0ZXMtbG9uZyE=";

    private final JwtService jwt = new JwtService(
            new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(7)));

    private static User user() throws Exception {
        User u = new User("a@b.dev", "A", "hash", Role.ADMIN);
        Field id = User.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(u, 42L);
        return u;
    }

    @Test
    void accessTokenRoundTrip() throws Exception {
        var principal = jwt.parseAccessToken(jwt.generateAccessToken(user())).orElseThrow();
        assertThat(principal.id()).isEqualTo(42L);
        assertThat(principal.email()).isEqualTo("a@b.dev");
        assertThat(principal.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void tokenTypesAreNotInterchangeable() throws Exception {
        assertThat(jwt.parseAccessToken(jwt.generateRefreshToken(user()))).isEmpty();
        assertThat(jwt.parseRefreshToken(jwt.generateAccessToken(user()))).isEmpty();
    }

    @Test
    void tamperedOrExpiredTokensAreRejected() throws Exception {
        String token = jwt.generateAccessToken(user());
        assertThat(jwt.parseAccessToken(token + "x")).isEmpty();

        var expiring = new JwtService(new JwtProperties(SECRET, Duration.ofSeconds(-1), Duration.ofDays(1)));
        assertThat(jwt.parseAccessToken(expiring.generateAccessToken(user()))).isEmpty();
    }
}
