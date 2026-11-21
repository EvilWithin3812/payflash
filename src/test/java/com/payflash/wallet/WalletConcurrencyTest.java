package com.payflash.wallet;

import com.payflash.AbstractIntegrationTest;
import com.payflash.auth.AuthDtos.SignupRequest;
import com.payflash.auth.AuthService;
import com.payflash.auth.UserRepository;
import com.payflash.common.BusinessException;
import com.payflash.TestUsers;
import com.payflash.wallet.WalletDtos.TransferRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hammers the wallet with concurrent requests against a real Postgres to prove:
 * no overdraft, no lost updates, no deadlocks, no double-spend on retried keys,
 * and the ledger always reconciles.
 */
class WalletConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    WalletService walletService;
    @Autowired
    AuthService authService;
    @Autowired
    UserRepository users;

    private Long newUser() {
        String email = TestUsers.randomEmail();
        authService.signup(new SignupRequest(email, "Concurrent User", "password123", true));
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    private String emailOf(Long userId) {
        return users.findById(userId).orElseThrow().getEmail();
    }

    /** Starts all tasks at the same instant to maximise contention. */
    private <T> List<Future<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<T> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // inspected by the caller
                }
            }
            return futures;
        }
    }

    @Test
    void concurrentTransfersNeverOverdraw() throws Exception {
        Long alice = newUser();
        Long bob = newUser();
        walletService.topUp(alice, new BigDecimal("1000.00"), UUID.randomUUID().toString());

        // 50 parallel transfers of 30 from a balance of 1000 -> exactly 33 can succeed.
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> {
                try {
                    walletService.transfer(alice, new TransferRequest(emailOf(bob), new BigDecimal("30.00"), null),
                            UUID.randomUUID().toString());
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    if ("INSUFFICIENT_FUNDS".equals(e.getCode())) {
                        insufficient.incrementAndGet();
                    }
                }
                return null;
            });
        }
        runConcurrently(tasks);

        assertThat(ok.get()).isEqualTo(33);
        assertThat(insufficient.get()).isEqualTo(17);
        assertThat(walletService.getWallet(alice).balance()).isEqualByComparingTo("10.00");
        assertThat(walletService.getWallet(bob).balance()).isEqualByComparingTo("990.00");
        assertThat(walletService.reconcile().healthy()).isTrue();
    }

    @Test
    void opposingTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        Long a = newUser();
        Long b = newUser();
        walletService.topUp(a, new BigDecimal("500"), UUID.randomUUID().toString());
        walletService.topUp(b, new BigDecimal("500"), UUID.randomUUID().toString());
        String emailA = emailOf(a);
        String emailB = emailOf(b);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            boolean aToB = i % 2 == 0;
            tasks.add(() -> {
                walletService.transfer(aToB ? a : b,
                        new TransferRequest(aToB ? emailB : emailA, new BigDecimal("7.25"), null),
                        UUID.randomUUID().toString());
                return null;
            });
        }
        List<Future<Void>> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(f -> assertThat(f.state()).isEqualTo(Future.State.SUCCESS));
        BigDecimal total = walletService.getWallet(a).balance().add(walletService.getWallet(b).balance());
        assertThat(total).isEqualByComparingTo("1000");
        assertThat(walletService.getWallet(a).balance()).isEqualByComparingTo("500");
        assertThat(walletService.reconcile().healthy()).isTrue();
    }

    @Test
    void concurrentRetriesWithSameKeyExecuteOnce() throws Exception {
        Long alice = newUser();
        Long bob = newUser();
        walletService.topUp(alice, new BigDecimal("100"), UUID.randomUUID().toString());
        String key = "retry-" + UUID.randomUUID();
        var request = new TransferRequest(emailOf(bob), new BigDecimal("25"), null);

        AtomicInteger fresh = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                var result = walletService.transfer(alice, request, key);
                (result.replayed() ? replayed : fresh).incrementAndGet();
                return null;
            });
        }
        runConcurrently(tasks);

        assertThat(fresh.get()).isEqualTo(1);
        assertThat(replayed.get()).isEqualTo(19);
        assertThat(walletService.getWallet(alice).balance()).isEqualByComparingTo("75");
        assertThat(walletService.getWallet(bob).balance()).isEqualByComparingTo("25");
    }
}
