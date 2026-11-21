package com.payflash.auth;

import com.payflash.AbstractIntegrationTest;
import com.payflash.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void signupCreatesUserWithEmptyWallet() throws Exception {
        var user = TestUsers.signup(mvc);

        mvc.perform(get("/api/v1/wallet").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        var user = TestUsers.signup(mvc);
        String body = """
                {"email":"%s","fullName":"Again","password":"password123","acceptTerms":true}
                """.formatted(user.email().toUpperCase());

        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void invalidSignupReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"fullName\":\"\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void loginWithWrongPasswordIs401() throws Exception {
        var user = TestUsers.signup(mvc);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"wrong-password\"}".formatted(user.email())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginSucceedsWithCorrectPassword() throws Exception {
        var user = TestUsers.signup(mvc);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(user.email())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void protectedEndpointsRequireToken() throws Exception {
        mvc.perform(get("/api/v1/wallet"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void refreshTokenIssuesNewTokensButAccessTokenCannotBeUsedToRefresh() throws Exception {
        var user = TestUsers.signup(mvc);

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(user.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(user.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
        var user = TestUsers.signup(mvc);
        mvc.perform(get("/api/v1/wallet").header("Authorization", "Bearer " + user.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointForbiddenForNormalUser() throws Exception {
        var user = TestUsers.signup(mvc);
        mvc.perform(get("/api/v1/admin/reconciliation").header("Authorization", user.bearer()))
                .andExpect(status().isForbidden());
    }
}
