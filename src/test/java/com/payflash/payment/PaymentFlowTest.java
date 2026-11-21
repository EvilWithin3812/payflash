package com.payflash.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflash.AbstractIntegrationTest;
import com.payflash.TestUsers;
import com.payflash.TestUsers.Registered;
import com.payflash.auth.UserRepository;
import com.payflash.payment.PaymentDtos.VerifyPaymentRequest;
import com.payflash.payment.PaymentGateway.GatewayOrder;
import com.payflash.wallet.WalletService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Razorpay top-up flow end to end, with the Razorpay HTTP API mocked out.
 * Signatures are real HMACs computed with the test secrets below.
 */
@TestPropertySource(properties = {
        "app.razorpay.key-id=rzp_test_payflash",
        "app.razorpay.key-secret=" + PaymentFlowTest.KEY_SECRET,
        "app.razorpay.webhook-secret=" + PaymentFlowTest.WEBHOOK_SECRET
})
class PaymentFlowTest extends AbstractIntegrationTest {

    static final String KEY_SECRET = "test_key_secret";
    static final String WEBHOOK_SECRET = "test_webhook_secret";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    PaymentService paymentService;
    @Autowired
    UserRepository users;
    @Autowired
    WalletService walletService;

    @MockitoBean
    PaymentGateway gateway;

    @BeforeEach
    void fakeRazorpay() {
        when(gateway.createOrder(anyLong(), anyString(), anyString())).thenAnswer(inv ->
                new GatewayOrder("order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14),
                        inv.getArgument(0), inv.getArgument(1), "created"));
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions createOrder(Registered user, String amount, String key) throws Exception {
        return mvc.perform(post("/api/v1/payments/topup-orders")
                .header("Authorization", user.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + "}"));
    }

    private String newOrder(Registered user, String amount) throws Exception {
        String json = createOrder(user, amount, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("orderId").asText();
    }

    private ResultActions verifyCheckout(Registered user, String orderId, String paymentId, String signature)
            throws Exception {
        return mvc.perform(post("/api/v1/payments/verify")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"razorpayOrderId":"%s","razorpayPaymentId":"%s","razorpaySignature":"%s"}
                        """.formatted(orderId, paymentId, signature)));
    }

    private static String checkoutSignature(String orderId, String paymentId) {
        return RazorpaySignatures.hmacHex((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8), KEY_SECRET);
    }

    private static String webhookBody(String event, String orderId, String paymentId, long amountPaise) {
        return """
                {"entity":"event","event":"%s","payload":{"payment":{"entity":{
                  "id":"%s","order_id":"%s","amount":%d,"currency":"INR","status":"captured"}}}}
                """.formatted(event, paymentId, orderId, amountPaise);
    }

    private ResultActions webhook(String body, String signature) throws Exception {
        return mvc.perform(post("/api/v1/payments/webhook")
                .header("X-Razorpay-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions signedWebhook(String body) throws Exception {
        return webhook(body, RazorpaySignatures.hmacHex(body.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET));
    }

    private BigDecimal balanceOf(Registered user) throws Exception {
        String json = mvc.perform(get("/api/v1/wallet").header("Authorization", user.bearer()))
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(mapper.readTree(json).get("balance").asText());
    }

    private JsonNode orderStatus(Registered user, String orderId) throws Exception {
        String json = mvc.perform(get("/api/v1/payments/orders/" + orderId).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void createOrderIsIdempotentAndReturnsCheckoutDetails() throws Exception {
        var user = TestUsers.signup(mvc);
        String key = UUID.randomUUID().toString();

        String first = createOrder(user, "499.50", key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amountMinor").value(49950))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.keyId").value("rzp_test_payflash"))
                .andReturn().getResponse().getContentAsString();
        String second = createOrder(user, "499.50", key)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(second).get("orderId")).isEqualTo(mapper.readTree(first).get("orderId"));
        verify(gateway, times(1)).createOrder(eq(49950L), eq("INR"), anyString());

        createOrder(user, "10.00", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void verifiedCheckoutCreditsWalletExactlyOnceEvenWithWebhook() throws Exception {
        var user = TestUsers.signup(mvc);
        String orderId = newOrder(user, "750.00");
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);

        verifyCheckout(user, orderId, paymentId, checkoutSignature(orderId, paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.transactionReference").isNotEmpty());
        // Browser retries, then Razorpay's webhook arrives for the same payment.
        verifyCheckout(user, orderId, paymentId, checkoutSignature(orderId, paymentId)).andExpect(status().isOk());
        signedWebhook(webhookBody("payment.captured", orderId, paymentId, 75000)).andExpect(status().isOk());

        assertThat(balanceOf(user)).isEqualByComparingTo("750.00");
        mvc.perform(get("/api/v1/wallet/transactions").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("TOPUP"))
                .andExpect(jsonPath("$.content[0].note").value("Razorpay top-up " + paymentId));
    }

    @Test
    void forgedCheckoutSignatureIsRejected() throws Exception {
        var user = TestUsers.signup(mvc);
        String orderId = newOrder(user, "100.00");

        verifyCheckout(user, orderId, "pay_fake", checkoutSignature(orderId, "pay_other"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        assertThat(balanceOf(user)).isEqualByComparingTo("0");
        assertThat(orderStatus(user, orderId).get("status").asText()).isEqualTo("CREATED");
    }

    @Test
    void userCannotConfirmSomeoneElsesOrder() throws Exception {
        var owner = TestUsers.signup(mvc);
        var attacker = TestUsers.signup(mvc);
        String orderId = newOrder(owner, "100.00");

        verifyCheckout(attacker, orderId, "pay_x", checkoutSignature(orderId, "pay_x"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/orders/" + orderId).header("Authorization", attacker.bearer()))
                .andExpect(status().isNotFound());
        assertThat(balanceOf(attacker)).isEqualByComparingTo("0");
    }

    @Test
    void webhookAloneCreditsWalletOnceAndReplaysAreIgnored() throws Exception {
        var user = TestUsers.signup(mvc);
        String orderId = newOrder(user, "250.00");
        String body = webhookBody("payment.captured", orderId, "pay_wh1", 25000);

        signedWebhook(body).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        signedWebhook(body).andExpect(status().isOk());
        signedWebhook(webhookBody("order.paid", orderId, "pay_wh1", 25000)).andExpect(status().isOk());

        assertThat(balanceOf(user)).isEqualByComparingTo("250.00");
        assertThat(orderStatus(user, orderId).get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void webhookWithBadSignatureOrMismatchedAmountDoesNotCredit() throws Exception {
        var user = TestUsers.signup(mvc);
        String orderId = newOrder(user, "300.00");

        webhook(webhookBody("payment.captured", orderId, "pay_bad", 30000), "deadbeef")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        // Correctly signed, but for less money than the order: accepted (so Razorpay stops retrying), not credited.
        signedWebhook(webhookBody("payment.captured", orderId, "pay_low", 100)).andExpect(status().isOk());

        assertThat(balanceOf(user)).isEqualByComparingTo("0");
        assertThat(orderStatus(user, orderId).get("status").asText()).isEqualTo("CREATED");
    }

    @Test
    void failedAttemptCanBeFollowedBySuccessfulPayment() throws Exception {
        var user = TestUsers.signup(mvc);
        String orderId = newOrder(user, "120.00");

        signedWebhook(webhookBody("payment.failed", orderId, "pay_fail", 12000)).andExpect(status().isOk());
        assertThat(orderStatus(user, orderId).get("status").asText()).isEqualTo("FAILED");

        signedWebhook(webhookBody("payment.captured", orderId, "pay_ok", 12000)).andExpect(status().isOk());
        assertThat(orderStatus(user, orderId).get("status").asText()).isEqualTo("PAID");
        assertThat(balanceOf(user)).isEqualByComparingTo("120.00");
    }

    @Test
    void concurrentVerifyAndWebhookCreditOnlyOnce() throws Exception {
        var user = TestUsers.signup(mvc);
        Long userId = users.findByEmailIgnoreCase(user.email()).orElseThrow().getId();
        String orderId = newOrder(user, "1000.00");
        String paymentId = "pay_race";
        var verifyReq = new VerifyPaymentRequest(orderId, paymentId, checkoutSignature(orderId, paymentId));
        byte[] body = webhookBody("payment.captured", orderId, paymentId, 100000).getBytes(StandardCharsets.UTF_8);
        String bodySig = RazorpaySignatures.hmacHex(body, WEBHOOK_SECRET);

        // 10 browser retries and 10 webhook deliveries, all released at the same instant.
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return paymentService.verifyCheckout(userId, verifyReq);
                }));
                futures.add(pool.submit(() -> {
                    start.await();
                    paymentService.handleWebhook(body, bodySig);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(); // every call must succeed: the losers simply see PAID
            }
        }

        assertThat(walletService.getWallet(userId).balance()).isEqualByComparingTo("1000.00");
        assertThat(walletService.history(userId, 0, 50).getTotalElements()).isEqualTo(1);
    }
}
