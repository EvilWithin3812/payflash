package com.payflash.auth;

import com.payflash.auth.AuthDtos.MeResponse;
import com.payflash.common.BusinessException;
import com.payflash.outbox.OutboxService;
import com.payflash.outbox.Topics;
import com.payflash.wallet.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The signed-in user's own account: profile and deletion. */
@Service
public class AccountService {

    /** Published to {@code user.accounts} so other services delete what they hold about the user. */
    public record AccountDeleted(Long userId, Instant deletedAt) {
    }

    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final PasswordEncoder passwordEncoder;
    private final WalletService wallets;
    private final OutboxService outbox;
    private final AccountMailer mailer;
    private final AuthService authService;
    private final TransactionPins pins;
    private final com.payflash.request.PaymentRequestRepository paymentRequests;

    public AccountService(UserRepository users, UserTokenRepository userTokens, PasswordEncoder passwordEncoder,
                          WalletService wallets, OutboxService outbox, AccountMailer mailer, AuthService authService,
                          TransactionPins pins, com.payflash.request.PaymentRequestRepository paymentRequests) {
        this.pins = pins;
        this.paymentRequests = paymentRequests;
        this.users = users;
        this.userTokens = userTokens;
        this.passwordEncoder = passwordEncoder;
        this.wallets = wallets;
        this.outbox = outbox;
        this.mailer = mailer;
        this.authService = authService;
    }

    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        return MeResponse.from(active(userId), pins.threshold());
    }

    /**
     * Deletes the account: personal data is removed, every session ends, and the notification service is
     * told to delete its copy. Money must not vanish with it, so the wallet has to be empty first.
     * Transactions and ledger entries stay (anonymised), because the books must still balance.
     */
    @Transactional
    public void delete(Long userId, String password) {
        User user = active(userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "WRONG_PASSWORD", "The password is not correct");
        }
        if (com.payflash.demo.DemoMode.isDemoAccount(user.getEmail())) {
            throw BusinessException.conflict("DEMO_ACCOUNT", "The shared demo accounts can't be deleted. Sign up to try deletion");
        }
        if (user.getRole() == Role.ADMIN) {
            throw BusinessException.conflict("ADMIN_ACCOUNT", "Admin accounts cannot be deleted from the app");
        }
        // Locks the wallet row, so a transfer arriving at the same moment waits and then finds no recipient.
        BigDecimal balance = wallets.lockedBalance(userId);
        if (balance.signum() > 0) {
            throw BusinessException.conflict("BALANCE_NOT_ZERO",
                    "Your wallet still has " + balance + ". Spend or send it before deleting your account");
        }
        String email = user.getEmail();
        String name = user.getFullName();
        userTokens.deleteAllFor(userId);
        // Nobody can pay or be paid through this account any more.
        paymentRequests.cancelAllPendingFor(userId, Instant.now());
        // A random hash nobody knows the password for: the account can never log in again.
        user.anonymise(passwordEncoder.encode(UUID.randomUUID().toString()));
        authService.publishRevocation(user);
        outbox.record("User", userId.toString(), "AccountDeleted", Topics.USER_ACCOUNTS,
                new AccountDeleted(userId, Instant.now()));
        mailer.sendAccountDeleted(email, name);
    }

    private User active(Long userId) {
        return users.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                        "Session expired. Please log in again."));
    }
}
