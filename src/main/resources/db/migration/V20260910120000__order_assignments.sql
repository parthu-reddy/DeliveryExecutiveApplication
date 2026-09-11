-- Who holds an order, and the OTPs that prove the handover.
--
-- Both facts previously lived only in Redis: `order:driver:lock:<id>` and the cached ORDER_ACCEPTED
-- payload, each with a 24-hour TTL. Two consequences, both observed in the 2026-09-10 review:
--
--   1. The status-update guard read `if (currentAssignee != null && !driverId.equals(...)) return;`
--      -- so once the lock key was gone, ANY driver could post a status for ANY order.
--   2. If the payload key was gone, the assigned driver got "Invalid Delivery OTP. Order payload not
--      found." and the order could never be completed by any means.
--
-- Redis keeps the dispatch working set (candidate pings, rejection counters, the delayed queue).
-- It stops being the record of who holds the order.
CREATE TABLE order_assignments (
    order_id      UUID PRIMARY KEY,
    driver_id     UUID        NOT NULL,
    pickup_otp    VARCHAR(6),
    delivery_otp  VARCHAR(6),
    state         VARCHAR(32) NOT NULL,
    assigned_at   TIMESTAMPTZ NOT NULL,
    released_at   TIMESTAMPTZ
);

-- "which order is this driver on" is asked on every status update.
CREATE INDEX idx_order_assignments_driver ON order_assignments(driver_id);
