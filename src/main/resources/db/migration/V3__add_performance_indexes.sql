-- V3: Add performance indexes for frequently queried columns
-- These indexes support findByStatus(), findByVerificationStatus(), and biometric audit trail queries

CREATE INDEX IF NOT EXISTS idx_executives_status ON delivery_executives(status);
CREATE INDEX IF NOT EXISTS idx_executives_verification_status ON delivery_executives(verification_status);
CREATE INDEX IF NOT EXISTS idx_biometric_executive ON biometric_verifications(executive_id);
CREATE INDEX IF NOT EXISTS idx_telemetry_executive_recorded ON telemetry_logs(executive_id, recorded_at DESC);
