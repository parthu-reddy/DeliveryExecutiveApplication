-- Migration V4: Fix TIMESTAMP to TIMESTAMPTZ mismatch issue

ALTER TABLE delivery_executives
    ALTER COLUMN created_at TYPE TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ;

ALTER TABLE executive_documents
    ALTER COLUMN created_at TYPE TIMESTAMPTZ;

ALTER TABLE executive_bank_details
    ALTER COLUMN verified_at TYPE TIMESTAMPTZ;

ALTER TABLE biometric_verifications
    ALTER COLUMN verification_time TYPE TIMESTAMPTZ;
