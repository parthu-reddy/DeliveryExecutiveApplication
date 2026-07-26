package com.fooddelivery.telemetry.repository;

import com.fooddelivery.telemetry.entity.TelemetryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TelemetryLogRepository extends JpaRepository<TelemetryLog, Long> {

    @Query(value = "SELECT * FROM telemetry_logs WHERE executive_id = :executiveId ORDER BY recorded_at DESC LIMIT 1", nativeQuery = true)
    Optional<TelemetryLog> findLatestLogByExecutiveId(@Param("executiveId") UUID executiveId);
}
