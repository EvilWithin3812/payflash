package com.payflash.auth;

/**
 * The authenticated principal, built from JWT claims (no DB lookup per request).
 *
 * @param tokenVersion the user's token version when the token was issued; see {@link TokenRevocations}
 * @param emailVerified whether the email was verified when the token was issued (refresh to update it)
 */
public record AuthUser(Long id, String email, Role role, int tokenVersion, boolean emailVerified) {

    public AuthUser(Long id, String email, Role role) {
        this(id, email, role, 0, true);
    }
}
