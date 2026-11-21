package com.payflash.sale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflash.AbstractKafkaIntegrationTest;
import com.payflash.TestUsers;
import com.payflash.TestUsers.Registered;
import com.payflash.auth.Role;
import com.payflash.auth.User;
import com.payflash.auth.UserRepository;
import com.payflash.common.BusinessException;
import com.payflash.sale.SaleDtos.CreateSaleRequest;
import com.payflash.sale.SaleDtos.SaleOrderResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flash sale end to end on real Redis (Lua), Kafka (order queue) and Postgres (orders, wallet).
 */
class SaleFlowTest extends AbstractKafkaIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    SaleService saleService;
    @Autowired
    SaleInventory inventory;
    @Autowired
    SaleOrderRepository orders;
    @Autowired
    UserRepository users;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcTemplate jdbc;

    // ------------------------------------------------------------------ helpers

    private SaleDtos.SaleResponse liveSale(int units, String price) {
        return saleService.create(new CreateSaleRequest("Test drop " + UUID.randomUUID(), "test", new BigDecimal(price),
                units, Instant.now().minusSeconds(5), Instant.now().plus(Duration.ofHours(1)), 120));
    }

    /** Users without a wallet top-up: enough for reserving (no BCrypt, so hundreds are created quickly). */
    private List<Long> rawUsers(int n) {
        List<Long> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(users.save(new User(TestUsers.randomEmail(), "Buyer", "not-a-real-hash", Role.USER)).getId());
        }
        return ids;
    }

    private static <T> List<Future<T>> runAtOnce(List<Callable<T>> tasks) throws Exception {
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 200))) {
            CountDownLatch start = new CountDownLatch(1);
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
        }
        return futures;
    }

    private ResultActions reserveViaApi(Registered user, long saleId) throws Exception {
        return mvc.perform(post("/api/v1/sales/" + saleId + "/reserve").header("Authorization", user.bearer()));
    }

    private UUID reserveAndAwaitHeld(Registered user, long saleId) throws Exception {
        String json = reserveViaApi(user, saleId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andReturn().getResponse().getContentAsString();
        UUID token = UUID.fromString(mapper.readTree(json).get("holdToken").asText());
        await().atMost(Duration.ofSeconds(20)).until(() -> orders.existsByHoldToken(token));
        return token;
    }

    private ResultActions pay(Registered user, UUID token) throws Exception {
        return mvc.perform(post("/api/v1/sales/orders/" + token + "/pay").header("Authorization", user.bearer()));
    }

    private void topUp(Registered user, String amount) throws Exception {
        mvc.perform(post("/api/v1/wallet/topup").header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    private BigDecimal balanceOf(Registered user) throws Exception {
        String json = mvc.perform(get("/api/v1/wallet").header("Authorization", user.bearer()))
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(mapper.readTree(json).get("balance").asText());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void threeHundredBuyersFiftyUnitsNoOversell() throws Exception {
        var sale = liveSale(50, "99.00");
        List<Long> buyers = rawUsers(300);

        AtomicInteger soldOut = new AtomicInteger();
        Set<Integer> units = ConcurrentHashMap.newKeySet();
        List<Callable<SaleOrderResponse>> tasks = new ArrayList<>();
        for (Long buyer : buyers) {
            tasks.add(() -> {
                try {
                    SaleOrderResponse r = saleService.reserve(buyer, sale.id());
                    units.add(r.unitNo());
                    return r;
                } catch (BusinessException e) {
                    if ("SOLD_OUT".equals(e.getCode())) {
                        soldOut.incrementAndGet();
                    }
                    throw e;
                }
            });
        }
        long granted = runAtOnce(tasks).stream().filter(f -> {
            try {
                f.get();
                return true;
            } catch (Exception e) {
                return false;
            }
        }).count();

        assertThat(granted).isEqualTo(50);
        assertThat(soldOut.get()).isEqualTo(250);
        assertThat(units).hasSize(50).allMatch(u -> u >= 1 && u <= 50); // every unit handed out exactly once
        assertThat(inventory.remaining(sale.id())).isZero();

        // The Kafka consumer turns all 50 reservations into rows; the partial unique indexes would reject a duplicate.
        await().atMost(Duration.ofSeconds(30))
                .until(() -> orders.countBySaleIdAndStatus(sale.id(), SaleOrder.Status.HELD) == 50);
        Integer distinctUnits = jdbc.queryForObject(
                "select count(distinct unit_no) from sale_orders where sale_id = ?", Integer.class, sale.id());
        assertThat(distinctUnits).isEqualTo(50);
    }

    @Test
    void oneUserCannotReserveTwiceEvenConcurrently() throws Exception {
        var sale = liveSale(10, "10.00");
        Long buyer = rawUsers(1).getFirst();

        List<Callable<SaleOrderResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> saleService.reserve(buyer, sale.id()));
        }
        long granted = runAtOnce(tasks).stream().filter(f -> !f.isCancelled() && succeeded(f)).count();

        assertThat(granted).isEqualTo(1);
        assertThat(inventory.remaining(sale.id())).isEqualTo(9);
    }

    private static boolean succeeded(Future<?> f) {
        try {
            f.get();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void reserveThenPayFromWallet() throws Exception {
        var sale = liveSale(5, "249.00");
        var buyer = TestUsers.signup(mvc);
        topUp(buyer, "1000");

        UUID token = reserveAndAwaitHeld(buyer, sale.id());
        mvc.perform(get("/api/v1/sales/orders/" + token).header("Authorization", buyer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HELD"));

        pay(buyer, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.transactionReference").isNotEmpty());
        pay(buyer, token).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID")); // retry is safe

        assertThat(balanceOf(buyer)).isEqualByComparingTo("751.00");
        mvc.perform(get("/api/v1/wallet/transactions").header("Authorization", buyer.bearer()))
                .andExpect(jsonPath("$.content[0].type").value("PURCHASE"))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"));
        mvc.perform(get("/api/v1/sales/orders").header("Authorization", buyer.bearer()))
                .andExpect(jsonPath("$[0].status").value("PAID"));
        // Ledger still balances with the merchant (NULL wallet) leg.
        mvc.perform(get("/api/v1/sales/" + sale.id())).andExpect(jsonPath("$.remainingUnits").value(4));
    }

    @Test
    void insufficientFundsKeepsTheHold() throws Exception {
        var sale = liveSale(5, "500.00");
        var buyer = TestUsers.signup(mvc);
        topUp(buyer, "100");
        UUID token = reserveAndAwaitHeld(buyer, sale.id());

        pay(buyer, token).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        topUp(buyer, "400");
        pay(buyer, token).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(balanceOf(buyer)).isEqualByComparingTo("0");
    }

    @Test
    void unpaidHoldExpiresAndUnitReturnsToPool() throws Exception {
        var sale = liveSale(1, "50.00");
        var buyer = TestUsers.signup(mvc);
        topUp(buyer, "100");
        UUID token = reserveAndAwaitHeld(buyer, sale.id());
        assertThat(inventory.remaining(sale.id())).isZero();

        // Fast-forward the clock for this hold instead of waiting 2 minutes.
        jdbc.update("update sale_orders set expires_at = now() - interval '1 second' where hold_token = ?", token);
        // The scheduled sweeper may already be on it; either way the hold ends up expired exactly once.
        saleService.expireHolds();
        await().atMost(Duration.ofSeconds(10)).until(() -> inventory.remaining(sale.id()) == 1);

        pay(buyer, token).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        mvc.perform(get("/api/v1/sales/orders/" + token).header("Authorization", buyer.bearer()))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(balanceOf(buyer)).isEqualByComparingTo("100.00");

        // The unit is back, but for someone else: one hold per person per sale, so a bot can't keep
        // re-holding stock it never pays for.
        reserveViaApi(buyer, sale.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RESERVED"));
        var other = TestUsers.signup(mvc);
        topUp(other, "100");
        UUID second = reserveAndAwaitHeld(other, sale.id());
        pay(other, second).andExpect(status().isOk());
    }

    @Test
    void saleWindowAndAccessRules() throws Exception {
        var buyer = TestUsers.signup(mvc);
        var upcoming = saleService.create(new CreateSaleRequest("Later", null, new BigDecimal("10.00"), 5,
                Instant.now().plus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(2)), 60));

        reserveViaApi(buyer, upcoming.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALE_NOT_STARTED"));
        mvc.perform(post("/api/v1/sales/" + upcoming.id() + "/reserve")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sales")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/sales/" + upcoming.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("UPCOMING"))
                .andExpect(jsonPath("$.remainingUnits").value(5));
        // Only admins create sales.
        mvc.perform(post("/api/v1/admin/sales").header("Authorization", buyer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // Nobody else can see or pay my reservation.
        var sale = liveSale(3, "10.00");
        UUID token = reserveAndAwaitHeld(buyer, sale.id());
        var stranger = TestUsers.signup(mvc);
        pay(stranger, token).andExpect(status().isNotFound());
    }

    @Test
    void inventoryIsRebuiltFromDatabaseAfterRedisDataLoss() throws Exception {
        var sale = liveSale(20, "5.00");
        List<Long> buyers = rawUsers(7);
        Set<UUID> tokens = new HashSet<>();
        for (Long b : buyers) {
            tokens.add(saleService.reserve(b, sale.id()).holdToken());
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> tokens.stream().allMatch(orders::existsByHoldToken));

        redis.delete(List.of(SaleInventory.unitsKey(sale.id()), SaleInventory.buyersKey(sale.id()),
                SaleInventory.initialisedKey(sale.id())));
        assertThat(inventory.remaining(sale.id())).isZero();

        saleService.rebuildInventory(sale.id());

        assertThat(inventory.remaining(sale.id())).isEqualTo(13);
        // Existing holders are still recognised after the rebuild.
        assertThatThrownBy(() -> saleService.reserve(buyers.getFirst(), sale.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ALREADY_RESERVED");
        JsonNode list = mapper.readTree(mvc.perform(get("/api/v1/sales")).andReturn().getResponse().getContentAsString());
        assertThat(list.isArray()).isTrue();
    }
}
