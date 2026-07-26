CREATE INDEX IF NOT EXISTS idx_delivery_exec_location_gist ON delivery_executives USING GIST (last_known_location);
