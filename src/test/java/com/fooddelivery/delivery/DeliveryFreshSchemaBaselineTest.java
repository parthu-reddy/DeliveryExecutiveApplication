package com.fooddelivery.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Static guard for the complete fresh-Dev baseline.
 *
 * <p>V1 uses PostGIS and therefore cannot be executed by the embedded H2 tests. This test makes
 * the schema contract reviewable without a Docker, Oracle, PostgreSQL, Redis, or other external
 * fixture. The companion persistence test exercises the mapped entities against H2.
 */
class DeliveryFreshSchemaBaselineTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    @Test
    void freshDevBuildShipsOneCompleteBaselineRatherThanLegacyIncrementalMigrations() throws IOException {
        try (Stream<Path> paths = Files.list(MIGRATIONS)) {
            List<String> migrations = paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            assertThat(migrations).containsExactly("V1__init_schema.sql");
        }
    }

    @Test
    void baselineContainsTheCurrentApplicationAndAssignmentSchemaContract() throws IOException {
        String sql = baseline();
        String executives = table(sql, "delivery_executives");
        String assignments = table(sql, "order_assignments");

        assertThat(sql).contains("CREATE EXTENSION IF NOT EXISTS postgis;");
        assertThat(executives).contains(
                "phone_number VARCHAR(20) NOT NULL UNIQUE",
                "vehicle_number VARCHAR(50) UNIQUE",
                "application_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'",
                "CHECK (application_status IN ('DRAFT','SUBMITTED','IN_REVIEW','APPROVED','REJECTED','SUSPENDED'))",
                "rejection_reason VARCHAR(500)",
                "submitted_at TIMESTAMPTZ",
                "reviewed_at TIMESTAMPTZ",
                "reviewed_by UUID",
                "verification_application_version BIGINT",
                "city_id VARCHAR(255)",
                "last_known_location geometry(Point, 4326)",
                "vehicle_type vehicle_class",
                "length(btrim(rejection_reason)) BETWEEN 10 AND 500");
        assertThat(executives).doesNotContain("verification_status verification_status");
        assertThat(sql).contains(
                "CREATE INDEX idx_delivery_application_queue ON delivery_executives(application_status, submitted_at, id);");
        // The unused /telemetry REST path and its location-log table were deleted 2026-10-11; rider positions live in
        // Redis, written only by RiderLocationGuard (RandomDocuments/RiderGpsAntiSpoofing_2026-10-11).
        assertThat(sql).doesNotContain("telemetry_logs");

        assertThat(assignments).contains(
                "order_id UUID PRIMARY KEY",
                "driver_id UUID NOT NULL",
                "pickup_otp VARCHAR(6)",
                "delivery_otp VARCHAR(6)",
                "state VARCHAR(32) NOT NULL",
                "assigned_at TIMESTAMPTZ NOT NULL",
                "payment_method VARCHAR(16)",
                "delivery_status VARCHAR(32)",
                "version INTEGER NOT NULL DEFAULT 0",
                "CHECK (payment_method IS NULL OR payment_method IN ('CARD','UPI','WALLET'))");
        assertThat(sql).contains("CREATE INDEX idx_order_assignments_driver ON order_assignments(driver_id);");
    }

    private static String baseline() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V1__init_schema.sql"), StandardCharsets.UTF_8);
    }

    private static String table(String sql, String name) {
        int start = sql.indexOf("CREATE TABLE " + name + " (");
        assertThat(start).as("V1 defines %s", name).isGreaterThanOrEqualTo(0);
        int end = sql.indexOf("\n);", start);
        assertThat(end).as("V1 closes %s", name).isGreaterThan(start);
        return sql.substring(start, end + 3);
    }
}
