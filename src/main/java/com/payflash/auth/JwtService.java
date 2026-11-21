package com.payflash.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    static final String CLAIM_TYPE = "typ";
    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_TOKEN_VERSION = "tv";
    static final String CLAIM_EMAIL_VERIFIED = "ev";
    static final String TYPE_ACCESS = "access";
    static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final JwtProperties props;
    /**
     * Built once and shared: a JwtParser is immutable and thread-safe. Building one per request made jjwt
     * look up its JSON deserializer by reflection every time, through the class loader's lock. Under a
     * flash-sale load test that lock was the main bottleneck (found with Java Flight Recorder).
     */
    private final JwtParser parser;

    public JwtService(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.secret()));
        this.parser = Jwts.parser().verifyWith(key).build();
    }

    public String generateAccessToken(User user) {
        return build(user, TYPE_ACCESS, props.accessTokenTtl());
    }

    public String generateRefreshToken(User user) {
        return build(user, TYPE_REFRESH, props.refreshTokenTtl());
    }

    public long accessTokenTtlSeconds() {
        return props.accessTokenTtl().toSeconds();
    }

    private String build(User user, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .claim(CLAIM_EMAIL_VERIFIED, user.isEmailVerified())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256) // fixed algorithm: other services verify with HS256
                .compact();
    }

    /** Returns the principal if the token is a valid, unexpired access token. */
    public Optional<AuthUser> parseAccessToken(String token) {
        return parse(token, TYPE_ACCESS);
    }

    /** Returns the principal if the token is a valid, unexpired refresh token. */
    public Optional<AuthUser> parseRefreshToken(String token) {
        return parse(token, TYPE_REFRESH);
    }

    private Optional<AuthUser> parse(String token, String expectedType) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            Integer version = claims.get(CLAIM_TOKEN_VERSION, Integer.class);
            Boolean verified = claims.get(CLAIM_EMAIL_VERIFIED, Boolean.class);
            return Optional.of(new AuthUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_EMAIL, String.class),
                    Role.valueOf(claims.get(CLAIM_ROLE, String.class)),
                    version == null ? 0 : version,
                    Boolean.TRUE.equals(verified)));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
