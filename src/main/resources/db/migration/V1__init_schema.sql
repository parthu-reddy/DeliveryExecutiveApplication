CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE delivery_executives (
    id UUID PRIMARY KEY,
    phone_number VARCHAR(20) NOT NULL UNIQUE,
    vehicle_number VARCHAR(50),
    status VARCHAR(50) NOT NULL,
    last_known_location geometry(Point, 4326),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    version INTEGER DEFAULT 0,
    photo_url VARCHAR(1024),
    email VARCHAR(255),
    full_name VARCHAR(255)
);
CREATE INDEX idx_delivery_executives_status ON delivery_executives(status);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'UNPROCESSED',
    processed_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    retry_count INT DEFAULT 0
);
CREATE INDEX idx_outbox_status_polling ON outbox_events(status, created_at) WHERE status IN ('UNPROCESSED', 'FAILED');
