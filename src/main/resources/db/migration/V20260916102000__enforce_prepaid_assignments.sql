DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM order_assignments
        WHERE payment_method IS NOT NULL AND payment_method NOT IN ('CARD', 'UPI', 'WALLET')
    ) THEN
        RAISE EXCEPTION 'Prepaid-only migration blocked: unsupported delivery assignment rows exist';
    END IF;
END $$;

ALTER TABLE order_assignments
    ADD CONSTRAINT chk_order_assignment_payment_method_prepaid
    CHECK (payment_method IS NULL OR payment_method IN ('CARD', 'UPI', 'WALLET'));
