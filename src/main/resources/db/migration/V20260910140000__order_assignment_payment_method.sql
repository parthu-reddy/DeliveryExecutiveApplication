-- How the order was paid, carried from ORDER_ACCEPTED into the assignment.
--
-- The rider posts a cashCollectedAmount at DELIVERED. Without knowing the order is COD the delivery
-- service cannot require one, and the customer service downstream is left booking a cash collection
-- for an amount nobody declared.
ALTER TABLE order_assignments ADD COLUMN payment_method VARCHAR(16);
