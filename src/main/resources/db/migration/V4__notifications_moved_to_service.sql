-- Notifications moved to the notification-service, which owns its own database.
-- The core app no longer reads or writes this table.
DROP TABLE IF EXISTS notifications;
