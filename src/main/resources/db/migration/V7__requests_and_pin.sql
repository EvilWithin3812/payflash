-- Transaction PIN: asked for transfers above a threshold (like a UPI PIN). Stored as a bcrypt hash.
-- Wrong guesses are counted here (not in Redis) so a Redis outage can never switch the protection off.
ALTER TABLE users
    ADD COLUMN transaction_pin_hash VARCHAR(100),
    ADD COLUMN pin_failed_attempts  INT NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_until     TIMESTAMPTZ;

-- "Please pay me": the requester asks the payer for money; the payer pays or declines.
-- A split bill is several requests that share one split_group.
CREATE TABLE payment_requests (
    id                    BIGSERIAL PRIMARY KEY,
    requester_id          BIGINT         NOT NULL REFERENCES users (id),
    payer_id              BIGINT         NOT NULL REFERENCES users (id),
    amount                NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    note                  VARCHAR(255),
    status                VARCHAR(16)    NOT NULL
        CHECK (status IN ('PENDING', 'PAID', 'DECLINED', 'CANCELLED')),
    split_group           UUID,
    transaction_reference UUID,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    resolved_at           TIMESTAMPTZ,
    CONSTRAINT payment_request_not_self CHECK (requester_id <> payer_id)
);

CREATE INDEX idx_payment_requests_payer ON payment_requests (payer_id, created_at DESC);
CREATE INDEX idx_payment_requests_requester ON payment_requests (requester_id, created_at DESC);
CREATE INDEX idx_payment_requests_split ON payment_requests (split_group) WHERE split_group IS NOT NULL;
