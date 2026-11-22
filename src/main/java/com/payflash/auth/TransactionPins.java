package com.payflash.auth;

import com.payflash.common.BusinessException;
import com.payflash.demo.DemoMode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction PIN: a 4-6 digit code asked for when sending more than the threshold (default ₹500),
 * like a UPI PIN. It protects the money even if someone gets hold of a logged-in session.
 *
 * <p>Stored as a bcrypt hash. Five wrong guesses lock it for 15 minutes. Wrong guesses are recorded in their
 * own database transaction, so the failed payment rolling back can't undo the count.
 */
@Service
public class TransactionPins {

    public static final String PIN_PATTERN = "\\d{4,6}";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TransactionTemplate separateTransaction;
    private final BigDecimal threshold;
    private final int maxAttempts;
    private final Duration lock;
    private final Clock clock;

    public TransactionPins(UserRepository users, PasswordEncoder encoder, PlatformTransactionManager txManager,
                           @Value("${app.wallet.pin-threshold:500}") BigDecimal threshold,
                           @Value("${app.wallet.pin-max-attempts:5}") int maxAttempts,
                           @Value("${app.wallet.pin-lock:15m}") Duration lock) {
        this.users = users;
        this.encoder = encoder;
        this.separateTransaction = new TransactionTemplate(txManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.threshold = threshold;
        this.maxAttempts = maxAttempts;
        this.lock = lock;
        this.clock = Clock.systemUTC();
    }

    /** Amounts above this need the PIN. */
    public BigDecimal threshold() {
        return threshold;
    }

    public boolean required(BigDecimal amount) {
        return amount.compareTo(threshold) > 0;
    }

    /** Sets or changes the PIN. Asks for the account password, so a stolen session alone can't change it. */
    @Transactional
    public void set(Long userId, String pin, String password) {
        User user = users.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> BusinessException.notFound("User not found"));
        if (DemoMode.isDemoAccount(user.getEmail())) {
            throw BusinessException.conflict("DEMO_ACCOUNT",
                    "The shared demo accounts keep the PIN shown on the login page. Sign up to set your own");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "WRONG_PASSWORD", "The password is not correct");
        }
        if (pin == null || !pin.matches(PIN_PATTERN)) {
            throw BusinessException.badRequest("INVALID_PIN", "The PIN must be 4 to 6 digits");
        }
        if (pin.chars().distinct().count() == 1) {
            throw BusinessException.badRequest("WEAK_PIN", "Choose a PIN that isn't one digit repeated");
        }
        user.setTransactionPin(encoder.encode(pin));
    }

    /**
     * Checks the PIN when {@code amount} needs one; does nothing for smaller amounts. Call it inside the
     * payment's transaction, before any money moves: a wrong PIN throws, and the payment rolls back.
     */
    public void verifyFor(Long userId, BigDecimal amount, String pin) {
        if (!required(amount)) {
            return;
        }
        User user = users.findById(userId).orElseThrow(() -> BusinessException.notFound("User not found"));
        if (!user.hasTransactionPin()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PIN_NOT_SET",
                    "Payments above ₹" + threshold.toPlainString() + " need a transaction PIN. Set one on the Account page");
        }
        Instant now = clock.instant();
        if (user.isPinLocked(now)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "PIN_LOCKED",
                    "Too many wrong PINs. Try again after " + TIME.format(user.getPinLockedUntil()));
        }
        if (pin == null || pin.isBlank()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PIN_REQUIRED",
                    "Enter your transaction PIN to send more than ₹" + threshold.toPlainString());
        }
        if (!pin.matches(PIN_PATTERN) || !encoder.matches(pin, user.getTransactionPinHash())) {
            int attemptsLeft = recordWrongPin(userId, now);
            if (attemptsLeft == 0) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "PIN_LOCKED",
                        "Too many wrong PINs. Your PIN is locked for " + lock.toMinutes() + " minutes");
            }
            throw new BusinessException(HttpStatus.FORBIDDEN, "WRONG_PIN",
                    "Wrong PIN. " + attemptsLeft + (attemptsLeft == 1 ? " attempt" : " attempts") + " left");
        }
        user.recordCorrectPin();
    }

    /** Records the wrong guess in its own transaction (it must survive the payment's rollback). */
    private int recordWrongPin(Long userId, Instant now) {
        Integer left = separateTransaction.execute(status -> {
            User fresh = users.findById(userId).orElseThrow();
            fresh.recordWrongPin(maxAttempts, now.plus(lock));
            users.saveAndFlush(fresh);
            return fresh.isPinLocked(now) ? 0 : maxAttempts - fresh.getPinFailedAttempts();
        });
        return left == null ? 0 : left;
    }
}
