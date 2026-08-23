package com.fooddelivery.delivery.entity;

import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.common.enums.VerificationStatus;
import com.fooddelivery.common.enums.VehicleClass;
import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.util.UUID;

@Entity
@Table(name = "delivery_executives", indexes = {
    @jakarta.persistence.Index(name = "idx_delivery_exec_location", columnList = "last_known_location")
})
@lombok.extern.slf4j.Slf4j
@lombok.Getter
@lombok.Setter
public class DeliveryExecutive {
@Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "full_name")
    private String fullName;
    @Column(name = "phone_number", unique = true)
    private String phoneNumber;
    @Column(name = "vehicle_number", unique = true)
    private String vehicleNumber;
    @Column(name = "photo_url")
    private String photoUrl;
    @Column(name = "email")
    private String email;
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
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
    @Column(name = "city_id")
    private String cityId;

    public void setStatus(DeliveryExecutiveStatus status) {
        if (this.status != status) {
            log.info("Delivery executive {} status changing from {} to {}", this.id, this.status, status);
        }
        this.status = status;
    }

    @Version
    @Column(name = "version")
    private Integer version;
    @JsonIgnore
    @Column(name = "last_known_location")
    private Point lastKnownLocation;
    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
