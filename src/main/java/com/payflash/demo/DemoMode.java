package com.payflash.demo;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Public demo mode ({@code DEMO_MODE=true}): for a live showcase where visitors (recruiters, HR) should be able
 * to try everything in one click. It turns on:
 * <ul>
 *   <li>shared demo accounts with play money, whose logins are shown on the login page;</li>
 *   <li>simulated top-ups ("Add money" without a real payment), even with {@code PRODUCTION=true};</li>
 *   <li>a flash sale that is always live, so there is always something to buy.</li>
 * </ul>
 * Demo accounts are protected: nobody can delete them, reset their password or log everyone out of them.
 * Real sign-ups still work normally, including email verification.
 */
@Component
public class DemoMode {

    public record DemoAccount(String email, String fullName) {
    }

    static final String DOMAIN = "@demo.payflash.app";

    /** Transaction PIN of the shared demo accounts, shown on the login page (play money only). */
    public static final String PIN = "1234";

    private static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount("priya" + DOMAIN, "Priya Sharma"),
            new DemoAccount("rahul" + DOMAIN, "Rahul Verma"));

    private final boolean enabled;
    private final String password;

    public DemoMode(@Value("${app.demo.enabled:false}") boolean enabled,
                    @Value("${app.demo.password:try-payflash-demo}") String password) {
        this.enabled = enabled;
        this.password = password;
    }

    public boolean enabled() {
        return enabled;
    }

    public String password() {
        return password;
    }

    public List<DemoAccount> accounts() {
        return enabled ? ACCOUNTS : List.of();
    }

    /** True for the shared demo logins (whatever mode we're in, so they stay protected if demo mode is turned off). */
    public static boolean isDemoAccount(String email) {
        return email != null && email.toLowerCase(Locale.ROOT).endsWith(DOMAIN);
    }
}
