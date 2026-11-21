package com.payflash.config;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * With {@code PRODUCTION=true}, refuses to start while any demo-only setting is still on. One forgotten
 * environment variable must not mean free money (simulated top-ups) or forgeable admin tokens (the JWT key
 * that is published in this repository).
 */
@Component
public class ProductionSafetyCheck {

    /** The development key committed in application.yml. Public, so it must never sign real tokens. */
    static final String DEV_JWT_SECRET = "ZGV2LW9ubHktc2VjcmV0LWNoYW5nZS1tZS1wbGVhc2UtMzItYnl0ZXMtbG9uZyE=";

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    public ProductionSafetyCheck(@Value("${app.production:false}") boolean production,
                                 @Value("${app.jwt.secret}") String jwtSecret,
                                 @Value("${app.payments.simulated-topup-enabled:false}") boolean simulatedTopUp,
                                 @Value("${app.mail.enabled:true}") boolean mailEnabled,
                                 @Value("${app.auth.require-verified-email:true}") boolean requireVerifiedEmail,
                                 @Value("${app.public-url:}") String publicUrl,
                                 @Value("${app.demo.enabled:false}") boolean demo) {
        List<String> problems = new ArrayList<>();
        if (DEV_JWT_SECRET.equals(jwtSecret)) {
            problems.add("JWT_SECRET is the public development key (generate one: openssl rand -base64 48)");
        }
        if (simulatedTopUp && !demo) { // demo mode = play money on purpose
            problems.add("SIMULATED_TOPUP_ENABLED is true (anyone could add free money)");
        }
        if (!mailEnabled) {
            problems.add("MAIL_ENABLED is false (users could not verify emails or reset passwords)");
        }
        if (!requireVerifiedEmail) {
            problems.add("REQUIRE_VERIFIED_EMAIL is false");
        }
        if (publicUrl.isBlank() || publicUrl.contains("localhost") || publicUrl.contains("127.0.0.1")) {
            problems.add("PUBLIC_URL must be the public address of the site, e.g. https://payflash.example.com");
        }
        if (problems.isEmpty()) {
            return;
        }
        if (production) {
            throw new IllegalStateException("Refusing to start with PRODUCTION=true: " + String.join("; ", problems));
        }
        log.info("Development settings in use (fine locally; set PRODUCTION=true on a public server to block them): {}",
                String.join("; ", problems));
    }
}
