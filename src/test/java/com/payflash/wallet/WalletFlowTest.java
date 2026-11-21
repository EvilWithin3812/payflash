package com.payflash.wallet;

import com.payflash.AbstractIntegrationTest;
import com.payflash.TestUsers;
import com.payflash.TestUsers.Registered;
import com.payflash.outbox.OutboxRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletFlowTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxRepository outbox;

    private ResultActions topUp(Registered user, String amount, String key) throws Exception {
        return mvc.perform(post("/api/v1/wallet/topup")
                .header("Authorization", user.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + "}"));
    }

    private ResultActions transfer(Registered from, String toEmail, String amount, String key) throws Exception {
        return mvc.perform(post("/api/v1/wallet/transfer")
                .header("Authorization", from.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientEmail\":\"%s\",\"amount\":%s,\"note\":\"dinner\"}".formatted(toEmail, amount)));
    }

    private ResultActions balanceOf(Registered user, String expected) throws Exception {
        return mvc.perform(get("/api/v1/wallet").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(Double.parseDouble(expected)));
    }

    @Test
    void topUpThenTransferMovesMoneyAndWritesOutboxEvents() throws Exception {
        var alice = TestUsers.signup(mvc);
        var bob = TestUsers.signup(mvc);
        long outboxBefore = outbox.count();

        topUp(alice, "500.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.type").value("TOPUP"))
                .andExpect(jsonPath("$.direction").value("CREDIT"));

        transfer(alice, bob.email(), "120.50", UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.direction").value("DEBIT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        balanceOf(alice, "379.50");
        balanceOf(bob, "120.50");
        assertThat(outbox.count() - outboxBefore).isEqualTo(2);
        // Event contract for consumers (notification-service): the people involved travel with the event.
        String transferEvent = outbox.findAll().stream()
                .filter(e -> e.getPayload().contains("\"TRANSFER\"") && e.getPayload().contains(bob.email()))
                .findFirst().orElseThrow().getPayload();
        assertThat(transferEvent)
                .contains("\"from\":{\"userId\":")
                .contains("\"email\":\"" + alice.email() + "\"")
                .contains("\"email\":\"" + bob.email() + "\"")
                .contains("\"note\":\"dinner\"");

        mvc.perform(get("/api/v1/wallet/transactions").header("Authorization", bob.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].note").value("dinner"));

        mvc.perform(get("/api/v1/wallet/ledger").header("Authorization", alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(379.50));
    }

    @Test
    void retryWithSameIdempotencyKeyDoesNotChargeTwice() throws Exception {
        var alice = TestUsers.signup(mvc);
        var bob = TestUsers.signup(mvc);
        topUp(alice, "100", UUID.randomUUID().toString()).andExpect(status().isCreated());

        String key = UUID.randomUUID().toString();
        String firstRef = com.jayway.jsonpath.JsonPath.read(
                transfer(alice, bob.email(), "40", key).andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(), "$.reference");

        transfer(alice, bob.email(), "40", key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.reference").value(firstRef));

        balanceOf(alice, "60");
        balanceOf(bob, "40");
    }

    @Test
    void reusingKeyForDifferentRequestIsRejected() throws Exception {
        var alice = TestUsers.signup(mvc);
        var bob = TestUsers.signup(mvc);
        topUp(alice, "100", UUID.randomUUID().toString());

        String key = UUID.randomUUID().toString();
        transfer(alice, bob.email(), "10", key).andExpect(status().isCreated());
        transfer(alice, bob.email(), "99", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void insufficientFundsLeavesBalancesUntouched() throws Exception {
        var alice = TestUsers.signup(mvc);
        var bob = TestUsers.signup(mvc);
        topUp(alice, "50", UUID.randomUUID().toString());

        transfer(alice, bob.email(), "50.01", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        balanceOf(alice, "50");
        balanceOf(bob, "0");
    }

    @Test
    void cannotTransferToSelfOrUnknownUser() throws Exception {
        var alice = TestUsers.signup(mvc);
        topUp(alice, "50", UUID.randomUUID().toString());

        transfer(alice, alice.email(), "5", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_TRANSFER"));
        transfer(alice, TestUsers.randomEmail(), "5", UUID.randomUUID().toString())
                .andExpect(status().isNotFound());
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        var alice = TestUsers.signup(mvc);
        mvc.perform(post("/api/v1/wallet/topup").header("Authorization", alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void amountValidation() throws Exception {
        var alice = TestUsers.signup(mvc);
        topUp(alice, "0", UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        topUp(alice, "10.123", UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        topUp(alice, "100000.01", UUID.randomUUID().toString()).andExpect(status().isBadRequest());
    }
}
