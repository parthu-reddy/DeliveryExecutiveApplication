-- A manual reassignment updates the durable assignment row in the same transaction as its
-- DRIVER_ASSIGNED outbox event. Optimistic versioning protects status/completion writes that race
-- with that override.
ALTER TABLE order_assignments
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;
