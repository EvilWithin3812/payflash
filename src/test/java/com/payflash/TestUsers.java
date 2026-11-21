package com.payflash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Helpers to create users through the real API. */
public final class TestUsers {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.Random RANDOM = new java.util.Random();

    private TestUsers() {
    }

    public record Registered(String email, String accessToken, String refreshToken) {
        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    public static String randomEmail() {
        return "user-" + UUID.randomUUID() + "@test.dev";
    }

    public static Registered signup(MockMvc mvc) throws Exception {
        String email = randomEmail();
        String body = """
                {"email":"%s","fullName":"Test User","password":"password123","acceptTerms":true}
                """.formatted(email);
        // A random client IP per signup, so tests with rate limiting on don't share one IP bucket.
        String ip = "10.%d.%d.%d".formatted(RANDOM.nextInt(256), RANDOM.nextInt(256), RANDOM.nextInt(1, 255));
        String json = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = MAPPER.readTree(json);
        return new Registered(email, node.get("accessToken").asText(), node.get("refreshToken").asText());
    }
}
