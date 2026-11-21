-- ============================================================
-- PayFlash schema v1: users, wallets, double-entry ledger, outbox
-- ============================================================

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    full_name     VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE wallets (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT         NOT NULL UNIQUE REFERENCES users (id),
    currency   VARCHAR(3)     NOT NULL DEFAULT 'INR',
    balance    NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    version    BIGINT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- One row per money movement (transfer / top-up).
CREATE TABLE transactions (
    id              BIGSERIAL PRIMARY KEY,
    reference       UUID           NOT NULL UNIQUE,
    type            VARCHAR(20)    NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    from_wallet_id  BIGINT REFERENCES wallets (id),
    to_wallet_id    BIGINT         NOT NULL REFERENCES wallets (id),
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    note            VARCHAR(255),
    initiated_by    BIGINT         NOT NULL REFERENCES users (id),
    idempotency_key VARCHAR(100)   NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_tx_idempotency UNIQUE (initiated_by, idempotency_key)
);

-- Double-entry ledger: every transaction writes balanced DEBIT/CREDIT rows.
-- Append-only: rows are never updated or deleted.
CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT         NOT NULL REFERENCES transactions (id),
    wallet_id      BIGINT REFERENCES wallets (id), -- NULL = external system account (e.g. payment gateway)
    direction      VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    balance_after  NUMERIC(19, 2),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_wallet ON ledger_entries (wallet_id, created_at DESC);
CREATE INDEX idx_tx_from ON transactions (from_wallet_id, created_at DESC);
CREATE INDEX idx_tx_to ON transactions (to_wallet_id, created_at DESC);

-- Transactional outbox: events written in the same DB transaction as the business change,
-- then published to Kafka by a background poller.
CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
