-- Enum types for strict application state management
CREATE TYPE verification_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'MANUAL_REVIEW');
CREATE TYPE document_type AS ENUM ('AADHAAR', 'PAN', 'DRIVING_LICENSE', 'RC', 'SELFIE');
CREATE TYPE vehicle_class AS ENUM ('BICYCLE', 'MCWG', 'LMV', 'EV_TWO_WHEELER');

-- Alter existing delivery_executives table to support verification fields
ALTER TABLE delivery_executives 
    ADD COLUMN IF NOT EXISTS verification_status verification_status DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS vehicle_type vehicle_class,
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT FALSE;

-- Bank Details & IMPS Penny Drop Verification Engine
CREATE TABLE executive_bank_details (
    bank_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    executive_id UUID REFERENCES delivery_executives(id) ON DELETE CASCADE,
    account_number VARCHAR(50) NOT NULL,
    ifsc_code VARCHAR(20) NOT NULL,
    bank_registered_name VARCHAR(255),
    penny_drop_status verification_status DEFAULT 'PENDING',
    name_match_score NUMERIC(4,3), -- Stores the Jaro-Winkler floating point score
    verified_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(executive_id)
);

-- External API Document Verification Logs (Sarathi, Vahan, Signzy)
CREATE TABLE executive_documents (
    document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    executive_id UUID REFERENCES delivery_executives(id) ON DELETE CASCADE,
    doc_type document_type NOT NULL,
    document_number VARCHAR(100) NOT NULL,
    document_url VARCHAR(512), -- Secure URL to Object Storage (e.g., AWS S3, Cloudflare R2)
    api_verification_status verification_status DEFAULT 'PENDING',
    api_raw_response JSONB, -- Stores the exact unparsed JSON payload for audit and debugging
    expiry_date DATE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Biometric Runtime Verification Logs (Selfie Checks)
CREATE TABLE biometric_verifications (
    verification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    executive_id UUID REFERENCES delivery_executives(id) ON DELETE CASCADE,
    selfie_url VARCHAR(512) NOT NULL,
    confidence_score NUMERIC(4,3) NOT NULL,
    is_live BOOLEAN NOT NULL,
    verification_time TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- High-Frequency Telemetry Log (Insert Heavy Table)
CREATE TABLE telemetry_logs (
    log_id BIGSERIAL PRIMARY KEY,
    executive_id UUID REFERENCES delivery_executives(id) ON DELETE CASCADE,
    location GEOGRAPHY(POINT, 4326) NOT NULL, -- PostGIS geospatial SRID 4326 format
    speed_kmh NUMERIC(5,2),
    is_mock_location BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Explicit Indexing for performance under heavy concurrency
CREATE INDEX idx_telemetry_executive ON telemetry_logs(executive_id);
CREATE INDEX idx_telemetry_location ON telemetry_logs USING GIST(location);
CREATE INDEX idx_docs_executive ON executive_documents(executive_id);
