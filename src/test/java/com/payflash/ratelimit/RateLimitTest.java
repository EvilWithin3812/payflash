package com.payflash.ratelimit;

import com.payflash.AbstractIntegrationTest;
import com.payflash.TestUsers;
import com.payflash.TestUsers.Registered;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rate limiting against a real Redis, with tiny limits so the tests run fast. */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth=3",
        "app.rate-limit.money=2",
        "app.rate-limit.api=5"
})
class RateLimitTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String randomIp() {
        var r = new java.util.Random();
        return "172.16.%d.%d".formatted(r.nextInt(256), r.nextInt(1, 255));
    }

    private ResultActions login(String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@test.dev\",\"password\":\"wrong-password\"}"));
    }

    private ResultActions transfer(Registered from, String toEmail) throws Exception {
        return mvc.perform(post("/api/v1/wallet/transfer")
                .header("Authorization", from.bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientEmail\":\"%s\",\"amount\":1}".formatted(toEmail)));
    }

    @Test
    void loginIsLimitedPerIp() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 3; i++) {
            login(ip).andExpect(status().isUnauthorized())
                    .andExpect(header().string("X-RateLimit-Limit", "3"));
        }
        login(ip).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // A different IP has its own bucket.
        login(randomIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void moneyEndpointsAreLimitedPerUserNotPerIp() throws Exception {
        var alice = TestUsers.signup(mvc);
        var bob = TestUsers.signup(mvc);
        mvc.perform(post("/api/v1/wallet/topup").header("Authorization", alice.bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"))
                .andExpect(status().isCreated());

        transfer(alice, bob.email()).andExpect(status().isCreated()); // 2nd money call (top-up was the 1st)
        transfer(alice, bob.email()).andExpect(status().isTooManyRequests());

        // Bob shares the same client IP in MockMvc but has his own bucket.
        transfer(bob, alice.email()).andExpect(status().isCreated());
        // Read-only calls use the separate, larger "api" bucket.
        mvc.perform(get("/api/v1/wallet").header("Authorization", alice.bearer())).andExpect(status().isOk());
    }

    @Test
    void webhookAndDocsAreNeverLimited() throws Exception {
        for (int i = 0; i < 8; i++) {
            // Not configured in this context -> 503, but never 429.
            mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isServiceUnavailable());
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        }
    }
}
