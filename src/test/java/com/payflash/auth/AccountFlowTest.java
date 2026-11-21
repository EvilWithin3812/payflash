package com.payflash.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflash.AbstractIntegrationTest;
import com.payflash.TestUsers;
import com.payflash.TestUsers.Registered;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Email verification, password reset, "log out everywhere", account deletion and the login lock. */
@TestPropertySource(properties = "app.auth.require-verified-email=true")
class AccountFlowTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    /** Captures the one-time tokens that would have been emailed. */
    @MockitoBean
    AccountMailer mailer;

    @Test
    void moneyNeedsAVerifiedEmailAndTheLinkWorksOnce() throws Exception {
        Registered user = TestUsers.signup(mvc);
        me(user).andExpect(jsonPath("$.emailVerified").value(false));
        topUp(user).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        String token = verificationTokenFor(user.email());
        postJson("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(token)).andExpect(status().isNoContent());
        postJson("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LINK"));

        // The old access token still says "unverified"; a refresh picks up the change.
        Registered refreshed = refresh(user);
        me(refreshed).andExpect(jsonPath("$.emailVerified").value(true));
        topUp(refreshed).andExpect(status().isCreated());
    }

    @Test
    void forgotPasswordNeverRevealsWhetherAnAccountExists() throws Exception {
        String nobody = "nobody-" + UUID.randomUUID() + "@test.dev";
        postJson("/api/v1/auth/forgot-password", "{\"email\":\"%s\"}".formatted(nobody)).andExpect(status().isAccepted());
        verify(mailer, never()).sendPasswordReset(eq(nobody), anyString(), anyString());
    }

    @Test
    void passwordResetChangesThePasswordAndLogsOutEveryDevice() throws Exception {
        Registered user = TestUsers.signup(mvc);
        postJson("/api/v1/auth/forgot-password", "{\"email\":\"%s\"}".formatted(user.email()))
                .andExpect(status().isAccepted());
        var captor = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendPasswordReset(eq(user.email()), anyString(), captor.capture());

        postJson("/api/v1/auth/reset-password",
                "{\"token\":\"%s\",\"newPassword\":\"brand-new-pass\"}".formatted(captor.getValue()))
                .andExpect(status().isNoContent());
        verify(mailer).sendPasswordChanged(eq(user.email()), anyString());

        // Old sessions are dead: the refresh token at once, the access token through the Redis check.
        postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(user.refreshToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/wallet").header("Authorization", user.bearer())).andExpect(status().isUnauthorized());

        login(user.email(), "password123").andExpect(status().isUnauthorized());
        login(user.email(), "brand-new-pass").andExpect(status().isOk());
    }

    @Test
    void logoutEverywhereEndsAllSessions() throws Exception {
        Registered user = TestUsers.signup(mvc);
        mvc.perform(post("/api/v1/users/me/logout-all").header("Authorization", user.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/wallet").header("Authorization", user.bearer())).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(user.refreshToken()))
                .andExpect(status().isUnauthorized());
        login(user.email(), "password123").andExpect(status().isOk());
    }

    @Test
    void deletingAnAccountRemovesPersonalDataAndBlocksLogin() throws Exception {
        Registered user = TestUsers.signup(mvc);
        deleteAccount(user, "wrong-password").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));

        deleteAccount(user, "password123").andExpect(status().isNoContent());
        verify(mailer).sendAccountDeleted(eq(user.email()), anyString());

        login(user.email(), "password123").andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/wallet").header("Authorization", user.bearer())).andExpect(status().isUnauthorized());

        // The address is free again, and nobody can send money to the deleted account.
        Registered sender = TestUsers.signup(mvc);
        String token = verificationTokenFor(sender.email());
        postJson("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(token)).andExpect(status().isNoContent());
        Registered verifiedSender = refresh(sender);
        topUp(verifiedSender).andExpect(status().isCreated());
        long deletedId = Long.parseLong(mapper.readTree(java.util.Base64.getUrlDecoder()
                .decode(user.accessToken().split("\\.")[1])).get("sub").asText());
        mvc.perform(post("/api/v1/wallet/transfer").header("Authorization", verifiedSender.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientEmail\":\"deleted-%d@deleted.invalid\",\"amount\":10}".formatted(deletedId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAccountWithMoneyCannotBeDeleted() throws Exception {
        Registered user = TestUsers.signup(mvc);
        postJson("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(verificationTokenFor(user.email())))
                .andExpect(status().isNoContent());
        Registered verified = refresh(user);
        topUp(verified).andExpect(status().isCreated());
        deleteAccount(verified, "password123").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BALANCE_NOT_ZERO"));
    }

    @Test
    void tooManyWrongPasswordsLockTheAccount() throws Exception {
        Registered user = TestUsers.signup(mvc);
        for (int i = 0; i < 5; i++) {
            login(user.email(), "wrong-password-" + i).andExpect(status().isUnauthorized());
        }
        login(user.email(), "password123").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void signupNeedsTermsAndAPlainName() throws Exception {
        String email = TestUsers.randomEmail();
        postJson("/api/v1/auth/signup", """
                {"email":"%s","fullName":"Asha Rao","password":"password123","acceptTerms":false}""".formatted(email))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.acceptTerms").exists());
        postJson("/api/v1/auth/signup", """
                {"email":"%s","fullName":"Visit http://evil.example","password":"password123","acceptTerms":true}"""
                .formatted(email))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.fullName").exists());
    }

    // ------------------------------------------------------------------ helpers

    private String verificationTokenFor(String email) {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendVerification(eq(email), any(), captor.capture());
        assertThat(captor.getValue()).hasSizeGreaterThan(40);
        return captor.getValue();
    }

    private Registered refresh(Registered user) throws Exception {
        String json = postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(user.refreshToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var node = mapper.readTree(json);
        return new Registered(user.email(), node.get("accessToken").asText(), node.get("refreshToken").asText());
    }

    private ResultActions me(Registered user) throws Exception {
        return mvc.perform(get("/api/v1/users/me").header("Authorization", user.bearer())).andExpect(status().isOk());
    }

    private ResultActions topUp(Registered user) throws Exception {
        return mvc.perform(post("/api/v1/wallet/topup").header("Authorization", user.bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"));
    }

    private ResultActions deleteAccount(Registered user, String password) throws Exception {
        return mvc.perform(delete("/api/v1/users/me").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"%s\"}".formatted(password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return postJson("/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
