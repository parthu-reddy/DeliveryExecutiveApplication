ALTER TABLE telemetry_logs ALTER COLUMN location TYPE geometry(Point, 4326) USING location::geometry;
