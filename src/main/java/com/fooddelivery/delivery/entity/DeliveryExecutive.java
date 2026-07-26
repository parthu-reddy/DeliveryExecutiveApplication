package com.fooddelivery.delivery.entity;

import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.common.enums.VerificationStatus;
import com.fooddelivery.common.enums.VehicleClass;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Entity
@Table(name = "delivery_executives")
@Data
@NoArgsConstructor
@Slf4j
public class DeliveryExecutive {

    @Id
    private UUID id;

    private String fullName;
    private String phoneNumber;
    private String vehicleNumber;
    private String photoUrl;
    private String email;

    @Enumerated(EnumType.STRING)
    private DeliveryExecutiveStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "verification_status")
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "vehicle_type")
    private VehicleClass vehicleType;

    @Column(name = "is_active")
    private boolean active = false;
    
    @Column(name = "last_biometric_verification_at")
    private OffsetDateTime lastBiometricVerificationAt;

    public void setStatus(DeliveryExecutiveStatus status) {
        if (this.status != status) {
            log.info("Delivery executive {} status changing from {} to {}", this.id, this.status, status);
        }
        this.status = status;
    }

    @Version
    private Integer version;

    @JsonIgnore
    private Point lastKnownLocation;

    @CreationTimestamp
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
