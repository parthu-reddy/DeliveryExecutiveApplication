-- The furthest delivery step the assigned rider has confirmed (AT_RESTAURANT, OUT_FOR_DELIVERY,
-- DELIVERED, FAILED), written in the same transaction as the outbox event that announces it.
--
-- The rider's active-order list is read from the customer service, which learns of each step from
-- that event a few seconds later. In between, a reload after an accepted pickup OTP showed the
-- pickup form again. /api/v1/delivery/orders/active now overlays this column, so the rider always
-- sees at least the step the delivery service has already accepted.
ALTER TABLE order_assignments ADD COLUMN delivery_status VARCHAR(32);
