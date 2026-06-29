CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    restaurant_id UUID NOT NULL,
    delivery_executive_id UUID,
    status VARCHAR(50) NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_restaurant_id ON orders(restaurant_id);
CREATE INDEX idx_orders_status ON orders(status);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'UNPROCESSED',
    processed_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_outbox_status_unprocessed ON outbox_events(status, created_at) WHERE status = 'UNPROCESSED';

-- Delivery Executives
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

CREATE TABLE restaurants (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    fssai_license_number VARCHAR(100),
    gstin VARCHAR(100),
    pan VARCHAR(10),
    cin VARCHAR(21),
    is_active BOOLEAN DEFAULT false,
    location geometry(Point, 4326),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_restaurant_location ON restaurants USING GIST(location);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone_number VARCHAR(20) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE menu_items (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(10, 2) NOT NULL,
    is_available BOOLEAN DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_menu_items_restaurant_id ON menu_items(restaurant_id);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    menu_item_id UUID NOT NULL REFERENCES menu_items(id),
    quantity INT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE ledgers (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    transaction_ref VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    balance_after DECIMAL(10, 2) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ledgers_account_id_created ON ledgers(account_id, created_at DESC);

CREATE TABLE payment_intents (
    id UUID PRIMARY KEY,
    internal_order_id UUID NOT NULL REFERENCES orders(id),
    gateway_order_id VARCHAR(255),
    amount DECIMAL(15,2) NOT NULL,
    status VARCHAR(50) DEFAULT 'INITIATED',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_positive_amount CHECK (amount > 0)
);
CREATE INDEX idx_payment_intents_internal_order_id ON payment_intents(internal_order_id);

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    owner_type VARCHAR(50) NOT NULL,
    owner_id UUID NOT NULL,
    balance DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    lock_version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX idx_ledger_accounts_owner ON ledger_accounts(owner_id, owner_type);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    account_id UUID REFERENCES ledger_accounts(id),
    direction VARCHAR(10) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);
