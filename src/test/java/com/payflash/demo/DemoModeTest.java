package com.payflash.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflash.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public demo: one-click logins with play money, an always-live sale, and protected shared accounts. */
@TestPropertySource(properties = {"app.demo.enabled=true", "app.auth.require-verified-email=true"})
class DemoModeTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void demoLoginsWorkWithPlayMoneyAndAreProtected() throws Exception {
        JsonNode info = json(mvc.perform(get("/api/v1/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.accounts.length()").value(2)));
        JsonNode account = info.get("accounts").get(0);

        String token = json(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(
                                account.get("email").asText(), account.get("password").asText())))
                .andExpect(status().isOk())).get("accessToken").asText();
        String bearer = "Bearer " + token;

        // Verified, with play money, and allowed to "add money" (verification is required in this test).
        mvc.perform(get("/api/v1/wallet").header("Authorization", bearer))
                .andExpect(jsonPath("$.balance").value(5000.0));
        mvc.perform(post("/api/v1/wallet/topup").header("Authorization", bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"))
                .andExpect(status().isCreated());

        // Visitors share these accounts: none of them may delete it or lock the others out.
        mvc.perform(delete("/api/v1/users/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(account.get("password").asText())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEMO_ACCOUNT"));
        mvc.perform(post("/api/v1/users/me/logout-all").header("Authorization", bearer))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/wallet").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test
    void thereIsAlwaysALiveSale() throws Exception {
        mvc.perform(get("/api/v1/sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.phase == 'LIVE')]").isNotEmpty());
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
