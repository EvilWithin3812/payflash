package com.payflash.demo;

import com.payflash.auth.Role;
import com.payflash.auth.User;
import com.payflash.auth.UserRepository;
import com.payflash.sale.SaleDtos.CreateSaleRequest;
import com.payflash.sale.SaleInventory;
import com.payflash.sale.SaleRepository;
import com.payflash.sale.SaleService;
import com.payflash.wallet.WalletService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the public demo ready to use: the demo accounts exist with play money, and a flash sale is always live.
 * Runs at start-up and then every minute. Only active with DEMO_MODE=true.
 */
@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    /** Topped back up to this when it falls below MIN_BALANCE, so visitors never run out of play money. */
    private static final BigDecimal TARGET_BALANCE = new BigDecimal("5000.00");
    private static final BigDecimal MIN_BALANCE = new BigDecimal("1000.00");

    private record Product(String name, String description, String price, int units) {
    }

    private static final List<Product> PRODUCTS = List.of(
            new Product("Wireless Earbuds Pro", "Noise-cancelling earbuds, 30-hour battery.", "499.00", 100),
            new Product("Smartwatch Lite", "Heart-rate, SpO2 and 7-day battery.", "799.00", 80),
            new Product("Limited Sneakers Drop", "Numbered pair from a limited run.", "999.00", 50),
            new Product("Mechanical Keyboard", "Hot-swappable switches, RGB.", "1299.00", 60));

    private final DemoMode demo;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final WalletService wallets;
    private final SaleService saleService;
    private final SaleRepository sales;
    private final SaleInventory inventory;
    private final TransactionTemplate tx;

    public DemoSeeder(DemoMode demo, UserRepository users, PasswordEncoder encoder, WalletService wallets,
                      SaleService saleService, SaleRepository sales, SaleInventory inventory,
                      TransactionTemplate tx) {
        this.demo = demo;
        this.users = users;
        this.encoder = encoder;
        this.wallets = wallets;
        this.saleService = saleService;
        this.sales = sales;
        this.inventory = inventory;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refresh() {
        try {
            demo.accounts().forEach(this::ensureAccount);
            ensureLiveSale();
        } catch (RuntimeException e) {
            log.warn("Demo refresh failed; will retry in a minute", e);
        }
    }

    private void ensureAccount(DemoMode.DemoAccount account) {
        tx.executeWithoutResult(status -> {
            User user = users.findByEmailIgnoreCase(account.email()).orElseGet(() -> {
                User created = new User(account.email(), account.fullName(), encoder.encode(demo.password()), Role.USER);
                created.markEmailVerified();
                created.acceptTerms();
                created = users.save(created);
                wallets.createWalletFor(created.getId());
                log.info("Created demo account {}", account.email());
                return created;
            });
            // Someone may have changed the password while logged in as the demo user: put it back.
            if (!encoder.matches(demo.password(), user.getPasswordHash())) {
                user.changePassword(encoder.encode(demo.password()));
            }
            // Same for the transaction PIN, which visitors need to send more than the PIN threshold.
            if (!user.hasTransactionPin() || !encoder.matches(DemoMode.PIN, user.getTransactionPinHash())
                    || user.isPinLocked(Instant.now())) {
                user.setTransactionPin(encoder.encode(DemoMode.PIN));
            }
            BigDecimal balance = wallets.getWallet(user.getId()).balance();
            if (balance.compareTo(MIN_BALANCE) < 0) {
                // Internal key (contains ':'), one refill per account per minute at most.
                String key = "demo:refill:" + user.getId() + ":" + Instant.now().getEpochSecond() / 60;
                wallets.topUp(user.getId(), TARGET_BALANCE.subtract(balance), key, "Demo play money");
            }
        });
    }

    /**
     * If no sale is live with stock left (or about to start), starts a new 30-minute sale, rotating through a few
     * products. A sold-out sale doesn't count, so visitors never find only "sold out".
     */
    private void ensureLiveSale() {
        Instant now = Instant.now();
        boolean buyable = sales.findNotEnded(now).stream()
                .anyMatch(s -> s.getStartsAt().isAfter(now)
                        ? s.getStartsAt().isBefore(now.plus(Duration.ofMinutes(5)))
                        : inventory.remaining(s.getId()) > 0);
        if (buyable) {
            return;
        }
        Product p = PRODUCTS.get((int) ((now.getEpochSecond() / 1800) % PRODUCTS.size()));
        saleService.create(new CreateSaleRequest(p.name(), p.description() + " Demo sale: play money only.",
                new BigDecimal(p.price()), p.units(), now, now.plus(Duration.ofMinutes(30)), 120));
        log.info("Started demo flash sale: {}", p.name());
    }
}
