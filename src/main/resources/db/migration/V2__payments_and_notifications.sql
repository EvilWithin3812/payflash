-- ============================================================
-- PayFlash schema v2: payment-gateway orders, notification log
-- ============================================================

-- One row per Razorpay order. The wallet is credited exactly once, when the order moves to PAID
-- (from either the client-side verify call or the webhook, whichever arrives first).
CREATE TABLE payment_orders (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT         NOT NULL REFERENCES users (id),
    provider              VARCHAR(20)    NOT NULL DEFAULT 'RAZORPAY',
    provider_order_id     VARCHAR(64)    NOT NULL UNIQUE,
    provider_payment_id   VARCHAR(64),
    amount                NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency              VARCHAR(3)     NOT NULL DEFAULT 'INR',
    status                VARCHAR(20)    NOT NULL CHECK (status IN ('CREATED', 'PAID', 'FAILED')),
    idempotency_key       VARCHAR(100)   NOT NULL,
    transaction_reference UUID,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_order_idempotency UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_payment_orders_user ON payment_orders (user_id, created_at DESC);

-- Notifications sent by the Kafka consumer. The unique key makes the consumer idempotent:
-- Kafka delivery is at-least-once, so the same event can arrive twice.
CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    event_reference UUID         NOT NULL,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    channel         VARCHAR(20)  NOT NULL,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_once UNIQUE (event_reference, user_id, channel)
);
