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


