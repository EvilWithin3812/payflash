package com.payflash.auth;

import com.payflash.wallet.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates (or promotes) an ADMIN account on startup when ADMIN_EMAIL and ADMIN_PASSWORD are set.
 * Saves editing the database by hand to create flash sales locally or in a load test.
 * Does nothing when the variables are not set.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final WalletService wallets;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder, WalletService wallets,
                          @Value("${app.bootstrap-admin.email:}") String email,
                          @Value("${app.bootstrap-admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.wallets = wallets;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        if (password.length() < 8) {
            log.warn("ADMIN_PASSWORD is shorter than 8 characters; admin bootstrap skipped");
            return;
        }
        var existing = users.findByEmailIgnoreCase(email.trim());
        if (existing.isPresent()) {
            // Never promote an existing account: whoever signed up first with this address (without proving
            // they own it) would become admin with their own password.
            if (existing.get().getRole() != Role.ADMIN) {
                log.error("ADMIN_EMAIL {} belongs to an existing non-admin account; NOT promoting it. "
                        + "Choose an address nobody has signed up with.", email);
            }
            return;
        }
        User admin = new User(email.trim().toLowerCase(), "Admin", encoder.encode(password), Role.ADMIN);
        admin.markEmailVerified();
        admin.acceptTerms();
        admin = users.save(admin);
        wallets.createWalletFor(admin.getId());
        log.info("Created ADMIN account {}", email);
    }
}
