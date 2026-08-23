package com.fooddelivery.telemetry.entity;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "telemetry_logs")
@lombok.Getter
@lombok.Setter
public class TelemetryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "executive_id")
    private DeliveryExecutive executive;
    // No columnDefinition: V1__init_schema.sql owns the real type (GEOGRAPHY(POINT,4326)
    // plus a GIST index). Naming it here only forced that literal into ddl-auto schema
    // generation, which H2 cannot parse. DeliveryExecutive.lastKnownLocation is the same
    // JTS Point with no columnDefinition and maps correctly on both dialects.
    @Column(name = "location", nullable = false)
    private Point location;
    @Column(name = "speed_kmh", precision = 5, scale = 2)
    private BigDecimal speedKmh;
    @Column(name = "is_mock_location", nullable = false)
    private boolean isMockLocation = false;
    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;











public void setMockLocation(final boolean isMockLocation) {
        this.isMockLocation = isMockLocation;
    }

}
