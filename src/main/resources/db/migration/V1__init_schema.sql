CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TYPE verification_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'MANUAL_REVIEW');
CREATE TYPE document_type AS ENUM ('AADHAAR', 'PAN', 'DRIVING_LICENSE', 'RC', 'SELFIE');
CREATE TYPE vehicle_class AS ENUM ('BICYCLE', 'MCWG', 'LMV', 'EV_TWO_WHEELER');

CREATE TABLE delivery_executives (
    id UUID PRIMARY KEY,
    phone_number VARCHAR(20) NOT NULL UNIQUE,
    vehicle_number VARCHAR(50),
    status VARCHAR(50) NOT NULL,
    last_known_location geometry(Point, 4326),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version INTEGER DEFAULT 0,
    photo_url VARCHAR(1024),
    email VARCHAR(255),
    full_name VARCHAR(255),
    last_biometric_verification_at TIMESTAMP WITH TIME ZONE,
    verification_status verification_status DEFAULT 'PENDING',
    vehicle_type vehicle_class,
    is_active BOOLEAN DEFAULT FALSE
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

CREATE TABLE telemetry_logs (
    log_id BIGSERIAL PRIMARY KEY,
    executive_id UUID REFERENCES delivery_executives(id) ON DELETE CASCADE,
    location GEOGRAPHY(POINT, 4326) NOT NULL, 
    speed_kmh NUMERIC(5,2),
    is_mock_location BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_delivery_executives_status ON delivery_executives(status);
CREATE INDEX idx_executives_status ON delivery_executives(status);
CREATE INDEX idx_telemetry_location ON telemetry_logs USING GIST(location);
CREATE INDEX idx_docs_executive ON executive_documents(executive_id);
CREATE INDEX IF NOT EXISTS idx_executives_verification_status ON delivery_executives(verification_status);
CREATE INDEX IF NOT EXISTS idx_biometric_executive ON biometric_verifications(executive_id);
CREATE INDEX IF NOT EXISTS idx_telemetry_executive_recorded ON telemetry_logs(executive_id, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_delivery_exec_location_gist ON delivery_executives USING GIST (last_known_location);
CREATE INDEX idx_telemetry_executive ON telemetry_logs(executive_id);
