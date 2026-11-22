package com.payflash.request;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payment requests, split bills, the transaction PIN and recent recipients. */
class RequestsPinAndRecipientsTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    OutboxRepository outbox;

    // ------------------------------------------------------------------ transaction PIN

    @Test
    void transfersAboveTheThresholdNeedTheRightPin() throws Exception {
        var asha = TestUsers.signup(mvc);
        var ravi = TestUsers.signup(mvc);
        topUp(asha, "5000");

        // ₹500 or less: no PIN needed.
        transfer(asha, ravi.email(), "500", null).andExpect(status().isCreated());
        // Above ₹500 without a PIN set: told to set one.
        transfer(asha, ravi.email(), "501", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PIN_NOT_SET"));

        setPin(asha, "4321", "wrong-password").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
        setPin(asha, "1111", "password123").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PIN"));
        setPin(asha, "12ab", "password123").andExpect(status().isBadRequest());
        setPin(asha, "4321", "password123").andExpect(status().isNoContent());
        me(asha).andExpect(jsonPath("$.pinSet").value(true)).andExpect(jsonPath("$.pinThreshold").value(500));

        transfer(asha, ravi.email(), "600", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PIN_REQUIRED"));
        transfer(asha, ravi.email(), "600", "9999").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_PIN"))
                .andExpect(jsonPath("$.message").value("Wrong PIN. 4 attempts left"));
        balance(asha, "4500.00");

        transfer(asha, ravi.email(), "600", "4321").andExpect(status().isCreated());
        balance(asha, "3900.00");
    }

    @Test
    void fiveWrongPinsLockThePinEvenForTheRightOne() throws Exception {
        var asha = TestUsers.signup(mvc);
        var ravi = TestUsers.signup(mvc);
        topUp(asha, "5000");
        setPin(asha, "2580", "password123").andExpect(status().isNoContent());

        for (int i = 0; i < 4; i++) {
            transfer(asha, ravi.email(), "700", "0000").andExpect(jsonPath("$.code").value("WRONG_PIN"));
        }
        transfer(asha, ravi.email(), "700", "0000").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PIN_LOCKED"));
        transfer(asha, ravi.email(), "700", "2580").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PIN_LOCKED"));
        balance(asha, "5000.00");

        // Setting a new PIN (with the account password) unlocks it.
        setPin(asha, "3690", "password123").andExpect(status().isNoContent());
        transfer(asha, ravi.email(), "700", "3690").andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ payment requests

    @Test
    void aRequestCanBePaidOnceAndMovesTheMoney() throws Exception {
        var asha = TestUsers.signup(mvc);
        var ravi = TestUsers.signup(mvc);
        topUp(ravi, "1000");
        long outboxBefore = outbox.count();

        long id = createRequest(asha, ravi.email(), "250", "Movie tickets")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("OUTGOING"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.otherParty.email").value(ravi.email()))
                .andReturnId();
        // The payer is told (the notification service emails them).
        assertThat(outbox.count() - outboxBefore).isEqualTo(1);

        mvc.perform(get("/api/v1/requests/summary").header("Authorization", ravi.bearer()))
                .andExpect(jsonPath("$.waitingForMe").value(1)).andExpect(jsonPath("$.waitingForOthers").value(0));
        mvc.perform(get("/api/v1/requests").param("direction", "INCOMING").header("Authorization", ravi.bearer()))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].direction").value("INCOMING"))
                .andExpect(jsonPath("$.content[0].otherParty.email").value(asha.email()))
                .andExpect(jsonPath("$.content[0].note").value("Movie tickets"));

        // Only the payer can pay it; anyone else gets "not found".
        action(asha, id, "pay").andExpect(status().isNotFound());

        action(ravi, id, "pay").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.transactionReference").isNotEmpty());
        balance(ravi, "750.00");
        balance(asha, "250.00");

        // A second click (or a race) can't pay twice.
        action(ravi, id, "pay").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_NOT_PENDING"));
        balance(ravi, "750.00");
        // Ravi's history shows the payment as a transfer to Asha.
        mvc.perform(get("/api/v1/wallet/transactions").header("Authorization", ravi.bearer()))
                .andExpect(jsonPath("$.content[0].counterparty.email").value(asha.email()))
                .andExpect(jsonPath("$.content[0].note").value("Movie tickets"));
    }

    @Test
    void declineCancelAndTheRules() throws Exception {
        var asha = TestUsers.signup(mvc);
        var ravi = TestUsers.signup(mvc);

        long declined = createRequest(asha, ravi.email(), "100", null).andReturnId();
        action(ravi, declined, "decline").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        action(ravi, declined, "pay").andExpect(status().isConflict());

        long cancelled = createRequest(asha, ravi.email(), "100", null).andReturnId();
        action(ravi, cancelled, "cancel").andExpect(status().isNotFound()); // only the requester can cancel
        action(asha, cancelled, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        createRequest(asha, asha.email(), "100", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_REQUEST"));
        createRequest(asha, "nobody-" + UUID.randomUUID() + "@test.dev", "100", null).andExpect(status().isNotFound());
        createRequest(asha, ravi.email(), "0.50", null).andExpect(status().isBadRequest());

        // Paying a request with no money fails cleanly and leaves it open.
        long broke = createRequest(asha, ravi.email(), "100", null).andReturnId();
        action(ravi, broke, "pay").andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/requests").param("direction", "OUTGOING").header("Authorization", asha.bearer()))
                .andExpect(jsonPath("$.content[0].id").value(broke))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    void payingABigRequestNeedsThePin() throws Exception {
        var asha = TestUsers.signup(mvc);
        var ravi = TestUsers.signup(mvc);
        topUp(ravi, "2000");
        setPin(ravi, "8642", "password123").andExpect(status().isNoContent());
        long id = createRequest(asha, ravi.email(), "1200", "Rent share").andReturnId();

        action(ravi, id, "pay").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIN_REQUIRED"));
        pay(ravi, id, "1111").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WRONG_PIN"));
        pay(ravi, id, "8642").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        balance(asha, "1200.00");
    }

    @Test
    void splittingABillSendsEachFriendAnEqualShare() throws Exception {
        var asha = TestUsers.signup(mvc);
        var ravi = TestUsers.signup(mvc);
        var meera = TestUsers.signup(mvc);

        // ₹900 between Asha and two friends: ₹300 each, Asha keeps her own share.
        split(asha, "900", true, ravi.email(), meera.email().toUpperCase(), ravi.email())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.myShare").value(300))
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.requests[0].amount").value(300))
                .andExpect(jsonPath("$.requests[1].amount").value(300))
                .andExpect(jsonPath("$.requests[0].splitGroup").isNotEmpty());

        // ₹100 between three friends only: the odd paisa goes to the first friend.
        split(asha, "100", false, ravi.email(), meera.email(), TestUsers.signup(mvc).email())
                .andExpect(jsonPath("$.myShare").value(0))
                .andExpect(jsonPath("$.requests[0].amount").value(33.34))
                .andExpect(jsonPath("$.requests[1].amount").value(33.33))
                .andExpect(jsonPath("$.requests[2].amount").value(33.33));

        split(asha, "100", false, asha.email()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_REQUEST"));
        mvc.perform(get("/api/v1/requests/summary").header("Authorization", ravi.bearer()))
                .andExpect(jsonPath("$.waitingForMe").value(2));
    }

    // ------------------------------------------------------------------ recent recipients

    @Test
    void recentRecipientsAreThePeopleIPaidNewestFirst() throws Exception {
        var asha = TestUsers.signup(mvc);
        var ravi = TestUsers.signup(mvc);
        var meera = TestUsers.signup(mvc);
        topUp(asha, "300");

        recipients(asha).andExpect(jsonPath("$.length()").value(0));
        transfer(asha, ravi.email(), "10", null).andExpect(status().isCreated());
        transfer(asha, meera.email(), "10", null).andExpect(status().isCreated());
        transfer(asha, ravi.email(), "10", null).andExpect(status().isCreated());

        recipients(asha)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].email").value(ravi.email()))
                .andExpect(jsonPath("$[1].email").value(meera.email()));
        // Money received doesn't make someone a recipient.
        recipients(ravi).andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------ helpers

    private void topUp(Registered user, String amount) throws Exception {
        mvc.perform(post("/api/v1/wallet/topup").header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    private ResultActions transfer(Registered from, String to, String amount, String pin) throws Exception {
        String pinJson = pin == null ? "null" : "\"" + pin + "\"";
        return mvc.perform(post("/api/v1/wallet/transfer").header("Authorization", from.bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientEmail\":\"%s\",\"amount\":%s,\"pin\":%s}".formatted(to, amount, pinJson)));
    }

    private void balance(Registered user, String expected) throws Exception {
        mvc.perform(get("/api/v1/wallet").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.balance").value(Double.parseDouble(expected)));
    }

    private ResultActions setPin(Registered user, String pin, String password) throws Exception {
        return mvc.perform(put("/api/v1/users/me/pin").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"%s\",\"password\":\"%s\"}".formatted(pin, password)));
    }

    private ResultActions me(Registered user) throws Exception {
        return mvc.perform(get("/api/v1/users/me").header("Authorization", user.bearer()));
    }

    private IdResult createRequest(Registered from, String payerEmail, String amount, String note) throws Exception {
        String noteJson = note == null ? "null" : "\"" + note + "\"";
        return new IdResult(mvc.perform(post("/api/v1/requests").header("Authorization", from.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payerEmail\":\"%s\",\"amount\":%s,\"note\":%s}".formatted(payerEmail, amount, noteJson))));
    }

    private ResultActions action(Registered user, long id, String action) throws Exception {
        return mvc.perform(post("/api/v1/requests/" + id + "/" + action).header("Authorization", user.bearer()));
    }

    private ResultActions pay(Registered user, long id, String pin) throws Exception {
        return mvc.perform(post("/api/v1/requests/" + id + "/pay").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + pin + "\"}"));
    }

    private ResultActions split(Registered from, String total, boolean includeMe, String... emails) throws Exception {
        return mvc.perform(post("/api/v1/requests/split").header("Authorization", from.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("totalAmount", total, "note", "Dinner",
                        "friendEmails", java.util.List.of(emails), "includeMe", includeMe))));
    }

    private ResultActions recipients(Registered user) throws Exception {
        return mvc.perform(get("/api/v1/wallet/recipients").header("Authorization", user.bearer()));
    }

    /** Lets a test chain expectations and then read the new request's id. */
    private final class IdResult {
        private final ResultActions actions;

        IdResult(ResultActions actions) {
            this.actions = actions;
        }

        IdResult andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        long andReturnId() throws Exception {
            String json = actions.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            return mapper.readTree(json).get("id").asLong();
        }
    }
}
