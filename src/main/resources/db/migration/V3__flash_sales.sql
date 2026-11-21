-- ============================================================
-- PayFlash schema v3: flash sales
-- ============================================================

-- Purchases debit a wallet and credit the (external) merchant account, so a transaction may now have
-- no destination wallet. Every transaction must still touch at least one wallet.
ALTER TABLE transactions ALTER COLUMN to_wallet_id DROP NOT NULL;
ALTER TABLE transactions ADD CONSTRAINT chk_tx_touches_wallet
    CHECK (from_wallet_id IS NOT NULL OR to_wallet_id IS NOT NULL);

CREATE TABLE sales (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(120)   NOT NULL,
    description  VARCHAR(1000),
    price        NUMERIC(19, 2) NOT NULL CHECK (price > 0),
    total_units  INT            NOT NULL CHECK (total_units BETWEEN 1 AND 100000),
    starts_at    TIMESTAMPTZ    NOT NULL,
    ends_at      TIMESTAMPTZ    NOT NULL,
    hold_seconds INT            NOT NULL CHECK (hold_seconds BETWEEN 30 AND 3600),
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_sale_window CHECK (ends_at > starts_at)
);

-- One row per successful reservation. Redis decides who gets a unit (fast path);
-- these rows are the durable record and the final safety net.
CREATE TABLE sale_orders (
    id                    BIGSERIAL PRIMARY KEY,
    sale_id               BIGINT         NOT NULL REFERENCES sales (id),
    user_id               BIGINT         NOT NULL REFERENCES users (id),
    unit_no               INT            NOT NULL CHECK (unit_no > 0),
    hold_token            UUID           NOT NULL UNIQUE,
    status                VARCHAR(20)    NOT NULL CHECK (status IN ('HELD', 'PAID', 'EXPIRED')),
    price                 NUMERIC(19, 2) NOT NULL CHECK (price > 0),
    expires_at            TIMESTAMPTZ    NOT NULL,
    transaction_reference UUID,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Last line of defence against overselling, even if Redis were wrong:
-- a unit can belong to only one live order, and a user can have only one live order per sale.
CREATE UNIQUE INDEX uq_sale_unit_live ON sale_orders (sale_id, unit_no) WHERE status IN ('HELD', 'PAID');
CREATE UNIQUE INDEX uq_sale_user_live ON sale_orders (sale_id, user_id) WHERE status IN ('HELD', 'PAID');

CREATE INDEX idx_sale_orders_expiring ON sale_orders (expires_at) WHERE status = 'HELD';
CREATE INDEX idx_sale_orders_user ON sale_orders (user_id, created_at DESC);
