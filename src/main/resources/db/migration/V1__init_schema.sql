-- Source: V1__init_schema.sql
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE delivery_executives (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(20) NOT NULL UNIQUE,
    vehicle_number VARCHAR(50),
    status VARCHAR(50) NOT NULL,
    last_known_location geometry(Point, 4326),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_delivery_executives_status ON delivery_executives(status);




-- Source: V2__add_outbox_events.sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'UNPROCESSED',
    processed_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_outbox_status_polling ON outbox_events(status, created_at) WHERE status IN ('UNPROCESSED', 'FAILED');


-- Source: V3__add_version_column.sql
ALTER TABLE delivery_executives ADD COLUMN version INTEGER DEFAULT 0;


-- Source: V10__add_retry_count_to_outbox.sql
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS retry_count INT DEFAULT 0;


