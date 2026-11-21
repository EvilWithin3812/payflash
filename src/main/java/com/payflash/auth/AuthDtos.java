package com.payflash.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AuthDtos {

    private static final String PASSWORD_RULE = "must be 8-72 characters";

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            // Names appear in emails other people receive, so no links, no line breaks, no markup:
            // letters (any language), spaces and . ' -
            @NotBlank @Size(max = 80)
            @Pattern(regexp = "^[\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*$", message = "may only contain letters, spaces and . ' -")
            String fullName,
            @NotBlank @Size(min = 8, max = 72, message = PASSWORD_RULE) String password,
            @AssertTrue(message = "you must accept the terms of service and privacy policy") boolean acceptTerms) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 72) String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {
    }

    public record VerifyEmailRequest(@NotBlank @Size(max = 100) String token) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 100) String token,
            @NotBlank @Size(min = 8, max = 72, message = PASSWORD_RULE) String newPassword) {
    }

    public record DeleteAccountRequest(@NotBlank @Size(max = 72) String password) {
    }

    public record MeResponse(Long id, String email, String fullName, Role role, boolean emailVerified,
                             Instant createdAt) {

        static MeResponse from(User u) {
            return new MeResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.isEmailVerified(),
                    u.getCreatedAt());
        }
    }
}
