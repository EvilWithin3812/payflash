-- ============================================================
-- V6: email verification, password reset, session revocation, account deletion, terms acceptance
-- ============================================================

ALTER TABLE users
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    -- Bumped on password reset, "log out everywhere" and deletion. Tokens carry the version they were
    -- issued with, so bumping it invalidates every refresh token (and, via Redis, every access token).
    ADD COLUMN token_version     INT NOT NULL DEFAULT 0,
    ADD COLUMN terms_accepted_at TIMESTAMPTZ,
    -- Deleted accounts are anonymised, not removed: their ledger rows must stay for the books to balance.
    ADD COLUMN deleted_at        TIMESTAMPTZ;

-- Accounts created before verification existed are treated as verified.
UPDATE users SET email_verified_at = created_at WHERE email_verified_at IS NULL;

-- Single-use tokens sent by email. Only a SHA-256 hash is stored: a database leak does not let anyone
-- verify an address or reset a password.
CREATE TABLE user_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    purpose    VARCHAR(20) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_tokens_user_purpose ON user_tokens (user_id, purpose);
