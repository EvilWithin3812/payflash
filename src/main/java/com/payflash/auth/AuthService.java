package com.payflash.auth;

import com.payflash.auth.AuthDtos.LoginRequest;
import com.payflash.auth.AuthDtos.SignupRequest;
import com.payflash.auth.AuthDtos.TokenResponse;
import com.payflash.common.BusinessException;
import com.payflash.wallet.WalletService;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final WalletService walletService;
    private final OneTimeTokens oneTimeTokens;
    private final AccountMailer mailer;
    private final TokenRevocations revocations;
    private final LoginThrottle loginThrottle;
    /** Checked against when the email is unknown, so a login takes as long whether or not the account exists. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       WalletService walletService, OneTimeTokens oneTimeTokens, AccountMailer mailer,
                       TokenRevocations revocations, LoginThrottle loginThrottle) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.walletService = walletService;
        this.oneTimeTokens = oneTimeTokens;
        this.mailer = mailer;
        this.revocations = revocations;
        this.loginThrottle = loginThrottle;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    /** Creates the user and their wallet atomically, and emails a verification link. */
    @Transactional
    public TokenResponse signup(SignupRequest req) {
        String email = req.email().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw BusinessException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = new User(email, req.fullName().trim(), passwordEncoder.encode(req.password()), Role.USER);
        user.acceptTerms();
        user = users.save(user);
        walletService.createWalletFor(user.getId());
        mailer.sendVerification(user.getEmail(), user.getFullName(),
                oneTimeTokens.issue(user.getId(), UserToken.Purpose.VERIFY_EMAIL));
        return tokensFor(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req) {
        if (loginThrottle.isLocked(req.email())) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                    "Too many wrong passwords. Try again in 15 minutes, or reset your password");
        }
        var user = users.findByEmailIgnoreCase(req.email().trim()).filter(u -> !u.isDeleted());
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(req.password(), hash);
        if (user.isEmpty() || !matches) {
            loginThrottle.recordFailure(req.email());
            throw new BadCredentialsException("bad credentials");
        }
        loginThrottle.reset(req.email());
        return tokensFor(user.get());
    }

    /**
     * Swaps a refresh token for a new pair. Rejected if the user has since reset their password, logged out
     * everywhere or deleted the account (their token version moved on). New tokens carry fresh claims, so
     * this is also how the client picks up a just-verified email.
     */
    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        AuthUser principal = jwtService.parseRefreshToken(refreshToken).orElseThrow(AuthService::invalidToken);
        User user = users.findById(principal.id())
                .filter(u -> !u.isDeleted() && u.getTokenVersion() == principal.tokenVersion())
                .orElseThrow(AuthService::invalidToken);
        return tokensFor(user);
    }

    @Transactional
    public void verifyEmail(String token) {
        Long userId = oneTimeTokens.redeem(token, UserToken.Purpose.VERIFY_EMAIL);
        users.findById(userId).filter(u -> !u.isDeleted()).ifPresent(User::markEmailVerified);
    }

    /** Silently does nothing if already verified or a link was sent in the last minute (stops email bombing). */
    @Transactional
    public void resendVerification(Long userId) {
        User user = activeUser(userId);
        if (user.isEmailVerified()
                || oneTimeTokens.issuedWithin(userId, UserToken.Purpose.VERIFY_EMAIL, RESEND_COOLDOWN)) {
            return;
        }
        mailer.sendVerification(user.getEmail(), user.getFullName(),
                oneTimeTokens.issue(userId, UserToken.Purpose.VERIFY_EMAIL));
    }

    /**
     * Always succeeds from the caller's point of view, so the endpoint can't be used to find out which emails
     * have accounts. A reset email is sent only if the account exists.
     */
    @Transactional
    public void forgotPassword(String email) {
        users.findByEmailIgnoreCase(email.trim())
                .filter(u -> !u.isDeleted())
                .filter(u -> !com.payflash.demo.DemoMode.isDemoAccount(u.getEmail()))
                .filter(u -> !oneTimeTokens.issuedWithin(u.getId(), UserToken.Purpose.RESET_PASSWORD, RESEND_COOLDOWN))
                .ifPresent(u -> mailer.sendPasswordReset(u.getEmail(), u.getFullName(),
                        oneTimeTokens.issue(u.getId(), UserToken.Purpose.RESET_PASSWORD)));
    }

    /** Sets the new password and logs out every device. The link proved ownership of the inbox, so it also verifies the email. */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        Long userId = oneTimeTokens.redeem(token, UserToken.Purpose.RESET_PASSWORD);
        User user = users.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> BusinessException.badRequest("INVALID_LINK", "This link is invalid or has expired."));
        user.changePassword(passwordEncoder.encode(newPassword));
        user.markEmailVerified();
        revokeAllSessions(user);
        loginThrottle.reset(user.getEmail());
        mailer.sendPasswordChanged(user.getEmail(), user.getFullName());
    }

    /** Logs the user out on every device: all refresh tokens stop working, and access tokens within seconds. */
    @Transactional
    public void logoutEverywhere(Long userId) {
        User user = activeUser(userId);
        if (com.payflash.demo.DemoMode.isDemoAccount(user.getEmail())) {
            // Shared by every visitor: one person must not be able to log everyone else out.
            throw BusinessException.conflict("DEMO_ACCOUNT", "Not available for the shared demo accounts");
        }
        revokeAllSessions(user);
    }

    void revokeAllSessions(User user) {
        user.revokeTokens();
        publishRevocation(user);
    }

    /** Tells every instance (through Redis) to reject access tokens older than the user's current version. */
    void publishRevocation(User user) {
        revocations.revokeOlderThan(user.getId(), user.getTokenVersion());
    }

    private User activeUser(Long userId) {
        return users.findById(userId).filter(u -> !u.isDeleted()).orElseThrow(AuthService::invalidToken);
    }

    private TokenResponse tokensFor(User user) {
        return new TokenResponse(
                jwtService.generateAccessToken(user),
                jwtService.generateRefreshToken(user),
                "Bearer",
                jwtService.accessTokenTtlSeconds());
    }

    private static BusinessException invalidToken() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Session expired. Please log in again.");
    }
}
