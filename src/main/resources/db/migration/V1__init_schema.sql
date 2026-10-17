CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TYPE verification_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'MANUAL_REVIEW');
CREATE TYPE document_type AS ENUM ('AADHAAR', 'PAN', 'DRIVING_LICENSE', 'RC', 'SELFIE');
CREATE TYPE vehicle_class AS ENUM ('BICYCLE', 'MCWG', 'LMV', 'EV_TWO_WHEELER');

CREATE TABLE delivery_executives (
    id UUID PRIMARY KEY,
    phone_number VARCHAR(20) NOT NULL UNIQUE,
    vehicle_number VARCHAR(50) UNIQUE,
    status VARCHAR(50) NOT NULL,
    last_known_location geometry(Point, 4326),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version INTEGER DEFAULT 0,
    photo_url VARCHAR(1024),
    email VARCHAR(255),
    full_name VARCHAR(255),
    last_biometric_verification_at TIMESTAMP WITH TIME ZONE,
    application_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT ck_delivery_application_status CHECK (application_status IN ('DRAFT','SUBMITTED','IN_REVIEW','APPROVED','REJECTED','SUSPENDED')),
    rejection_reason VARCHAR(500),
    submitted_at TIMESTAMPTZ,
    reviewed_at TIMESTAMPTZ,
    reviewed_by UUID,
    verification_application_version BIGINT,
    city_id VARCHAR(255),
    vehicle_type vehicle_class,
    is_active BOOLEAN DEFAULT FALSE,
    CONSTRAINT ck_delivery_application_reason CHECK (application_status NOT IN ('REJECTED','SUSPENDED') OR
        (rejection_reason IS NOT NULL AND length(btrim(rejection_reason)) BETWEEN 10 AND 500))
);

CREATE TABLE executive_bank_details (
    bank_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    executive_id UUID REFERENCES delivery_executives(id) ON DELETE CASCADE,
    account_number VARCHAR(50) NOT NULL,
    ifsc_code VARCHAR(20) NOT NULL,
    bank_registered_name VARCHAR(255),
    penny_drop_status verification_status DEFAULT 'PENDING',
    name_match_score NUMERIC(4,3), 
    verified_at TIMESTAMPTZ,
    UNIQUE(executive_id)
);

CREATE TABLE executive_documents (
    document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    executive_id UUID REFERENCES delivery_executives(id) ON DELETE CASCADE,
    doc_type document_type NOT NULL,
    document_number VARCHAR(100) NOT NULL,
    document_url VARCHAR(512), 
    api_verification_status verification_status DEFAULT 'PENDING',
    api_raw_response JSONB, 
    expiry_date DATE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE biometric_verifications (
    verification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    executive_id UUID REFERENCES delivery_executives(id) ON DELETE CASCADE,
    selfie_url VARCHAR(512) NOT NULL,
    confidence_score NUMERIC(4,3) NOT NULL,
    is_live BOOLEAN NOT NULL,
    verification_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);












-- Riders in one status untouched since a cutoff (the stale-online sweep); also every lookup by status. Was
-- (status) twice under two names.
CREATE INDEX idx_delivery_executives_status_updated ON delivery_executives(status, updated_at);

CREATE INDEX idx_docs_executive ON executive_documents(executive_id);

CREATE INDEX idx_delivery_application_queue ON delivery_executives(application_status, submitted_at, id);

CREATE INDEX IF NOT EXISTS idx_biometric_executive ON biometric_verifications(executive_id);

CREATE INDEX IF NOT EXISTS idx_delivery_exec_location_gist ON delivery_executives USING GIST (last_known_location);


-- Durable assignment authority and concurrency, present on every fresh Dev database.
CREATE TABLE order_assignments (
    order_id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
    pickup_otp VARCHAR(6),
    delivery_otp VARCHAR(6),
    state VARCHAR(32) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    payment_method VARCHAR(16),
    delivery_status VARCHAR(32),
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_order_assignment_payment_method_prepaid CHECK (payment_method IS NULL OR payment_method IN ('CARD','UPI','WALLET'))
);
CREATE INDEX idx_order_assignments_driver ON order_assignments(driver_id);
