package com.fooddelivery.telemetry.entity;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "telemetry_logs")
public class TelemetryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long logId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "executive_id")
    private DeliveryExecutive executive;

    @Column(nullable = false, columnDefinition = "geography(Point, 4326)")
    private Point location;

    @Column(precision = 5, scale = 2)
    private BigDecimal speedKmh;

    @Column(nullable = false)
    private boolean isMockLocation = false;

    @Column(nullable = false)
    private OffsetDateTime recordedAt;
}
