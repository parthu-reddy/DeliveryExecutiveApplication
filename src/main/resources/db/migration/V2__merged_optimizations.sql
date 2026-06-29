-- From V5__production_indexes_part2.sql
-- Add missing index on foreign key in order_items
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);

-- Add missing index on ledger_entries account_id
CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_id ON ledger_entries(account_id);


-- From V6__refunds_and_webhooks.sql
CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    masked_payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    payment_intent_id UUID NOT NULL REFERENCES payment_intents(id),
    amount DECIMAL(15,2) NOT NULL,
    status VARCHAR(50) DEFAULT 'PROCESSED',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_refund_positive CHECK (amount > 0)
);

CREATE INDEX idx_refunds_payment_intent_id ON refunds(payment_intent_id);

ALTER TABLE payment_intents ADD COLUMN refunded_amount DECIMAL(15,2) DEFAULT 0.00;
ALTER TABLE payment_intents ADD CONSTRAINT chk_refund_limits CHECK (refunded_amount <= amount);

CREATE OR REPLACE FUNCTION update_refunded_amount()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE payment_intents
        SET refunded_amount = COALESCE(refunded_amount, 0) + NEW.amount
        WHERE id = NEW.payment_intent_id;
    ELSIF TG_OP = 'UPDATE' THEN
        UPDATE payment_intents
        SET refunded_amount = COALESCE(refunded_amount, 0) - OLD.amount + NEW.amount
        WHERE id = NEW.payment_intent_id;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE payment_intents
        SET refunded_amount = COALESCE(refunded_amount, 0) - OLD.amount
        WHERE id = OLD.payment_intent_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refunds_update_payment_intents
AFTER INSERT OR UPDATE OR DELETE ON refunds
FOR EACH ROW EXECUTE FUNCTION update_refunded_amount();


-- From V7__order_optimistic_locking.sql
ALTER TABLE orders ADD COLUMN version INT DEFAULT 0;


-- From V8__performance_indexes.sql
-- Add index for payment_intents to avoid full table scan during reconcileStuckPayments cron job
CREATE INDEX IF NOT EXISTS idx_payment_intents_status_created_at ON payment_intents(status, created_at);

-- Drop the old partial index for outbox events that only covered 'UNPROCESSED'
DROP INDEX IF EXISTS idx_outbox_status_unprocessed;

-- Create a new partial index that covers both 'UNPROCESSED' and 'FAILED' for OutboxEventPoller
CREATE INDEX idx_outbox_status_polling ON outbox_events(status, created_at) WHERE status IN ('UNPROCESSED', 'FAILED');
