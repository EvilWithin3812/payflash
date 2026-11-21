-- Notification service schema. This database belongs to this service alone (database-per-service):
-- user ids are plain numbers here, not foreign keys into the core app's database.
CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    event_reference UUID          NOT NULL,
    user_id         BIGINT        NOT NULL,
    channel         VARCHAR(20)   NOT NULL,
    recipient       VARCHAR(255)  NOT NULL,
    subject         VARCHAR(255)  NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Kafka delivers at-least-once: this key makes a redelivered event a no-op.
    CONSTRAINT uq_notification_once UNIQUE (event_reference, user_id, channel)
);

CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
