package com.payflash.auth;

import com.payflash.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Creates and redeems the single-use tokens in verification and password-reset links. */
@Component
public class OneTimeTokens {

    static final Duration VERIFY_EMAIL_TTL = Duration.ofHours(24);
    static final Duration RESET_PASSWORD_TTL = Duration.ofMinutes(30);

    private final SecureRandom random = new SecureRandom();
    private final UserTokenRepository repository;

    public OneTimeTokens(UserTokenRepository repository) {
        this.repository = repository;
    }

    /** Replaces any earlier token of the same purpose (only the newest link works) and returns the raw token. */
    public String issue(Long userId, UserToken.Purpose purpose) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Duration ttl = purpose == UserToken.Purpose.VERIFY_EMAIL ? VERIFY_EMAIL_TTL : RESET_PASSWORD_TTL;
        repository.deleteAllFor(userId, purpose);
        repository.save(new UserToken(userId, purpose, hash(token), Instant.now().plus(ttl)));
        return token;
    }

    /** Marks the token used and returns its user id. Same error for unknown, used, expired and wrong-purpose tokens. */
    public Long redeem(String token, UserToken.Purpose purpose) {
        UserToken stored = repository.lockByHash(hash(token))
                .filter(t -> t.getPurpose() == purpose && t.isUsable(Instant.now()))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_LINK",
                        "This link is invalid or has expired. Ask for a new one."));
        stored.markUsed();
        return stored.getUserId();
    }

    /** True if a token of this purpose was issued less than {@code window} ago (to throttle resends). */
    public boolean issuedWithin(Long userId, UserToken.Purpose purpose, Duration window) {
        return repository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose)
                .map(t -> t.getCreatedAt().isAfter(Instant.now().minus(window)))
                .orElse(false);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
