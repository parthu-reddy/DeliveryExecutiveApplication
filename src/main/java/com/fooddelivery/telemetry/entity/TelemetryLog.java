package com.fooddelivery.telemetry.entity;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "telemetry_logs")
public class TelemetryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "executive_id")
    private DeliveryExecutive executive;
    @Column(name = "location", nullable = false, columnDefinition = "geography(Point, 4326)")
    private Point location;
    @Column(name = "speed_kmh", precision = 5, scale = 2)
    private BigDecimal speedKmh;
    @Column(name = "is_mock_location", nullable = false)
    private boolean isMockLocation = false;
    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    @java.lang.SuppressWarnings("all")
    public Long getLogId() {
        return this.logId;
    }

    @java.lang.SuppressWarnings("all")
    public DeliveryExecutive getExecutive() {
        return this.executive;
    }

    @java.lang.SuppressWarnings("all")
    public Point getLocation() {
        return this.location;
    }

    @java.lang.SuppressWarnings("all")
    public BigDecimal getSpeedKmh() {
        return this.speedKmh;
    }

    @java.lang.SuppressWarnings("all")
    public boolean isMockLocation() {
        return this.isMockLocation;
    }

    @java.lang.SuppressWarnings("all")
    public OffsetDateTime getRecordedAt() {
        return this.recordedAt;
    }

    @java.lang.SuppressWarnings("all")
    public void setLogId(final Long logId) {
        this.logId = logId;
    }

    @java.lang.SuppressWarnings("all")
    public void setExecutive(final DeliveryExecutive executive) {
        this.executive = executive;
    }

    @java.lang.SuppressWarnings("all")
    public void setLocation(final Point location) {
        this.location = location;
    }

    @java.lang.SuppressWarnings("all")
    public void setSpeedKmh(final BigDecimal speedKmh) {
        this.speedKmh = speedKmh;
    }

    @java.lang.SuppressWarnings("all")
    public void setMockLocation(final boolean isMockLocation) {
        this.isMockLocation = isMockLocation;
    }

    @java.lang.SuppressWarnings("all")
    public void setRecordedAt(final OffsetDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }
}
