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

public UUID getId() {
        return this.id;
    }

public String getFullName() {
        return this.fullName;
    }

public String getPhoneNumber() {
        return this.phoneNumber;
    }

public String getVehicleNumber() {
        return this.vehicleNumber;
    }

public String getPhotoUrl() {
        return this.photoUrl;
    }

public String getEmail() {
        return this.email;
    }

public DeliveryExecutiveStatus getStatus() {
        return this.status;
    }

public VerificationStatus getVerificationStatus() {
        return this.verificationStatus;
    }

public VehicleClass getVehicleType() {
        return this.vehicleType;
    }

public boolean isActive() {
        return this.active;
    }

public OffsetDateTime getLastBiometricVerificationAt() {
        return this.lastBiometricVerificationAt;
    }

public String getCityId() {
        return this.cityId;
    }

public Integer getVersion() {
        return this.version;
    }

public Point getLastKnownLocation() {
        return this.lastKnownLocation;
    }

public OffsetDateTime getCreatedAt() {
        return this.createdAt;
    }

public OffsetDateTime getUpdatedAt() {
        return this.updatedAt;
    }

public void setId(final UUID id) {
        this.id = id;
    }

public void setFullName(final String fullName) {
        this.fullName = fullName;
    }

public void setPhoneNumber(final String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

public void setVehicleNumber(final String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

public void setPhotoUrl(final String photoUrl) {
        this.photoUrl = photoUrl;
    }

public void setEmail(final String email) {
        this.email = email;
    }

public void setVerificationStatus(final VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

public void setVehicleType(final VehicleClass vehicleType) {
        this.vehicleType = vehicleType;
    }

public void setActive(final boolean active) {
        this.active = active;
    }

public void setLastBiometricVerificationAt(final OffsetDateTime lastBiometricVerificationAt) {
        this.lastBiometricVerificationAt = lastBiometricVerificationAt;
    }

public void setCityId(final String cityId) {
        this.cityId = cityId;
    }

public void setVersion(final Integer version) {
        this.version = version;
    }

    @JsonIgnore
public void setLastKnownLocation(final Point lastKnownLocation) {
        this.lastKnownLocation = lastKnownLocation;
    }

public void setCreatedAt(final OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

public void setUpdatedAt(final OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @java.lang.Override
public boolean equals(final java.lang.Object o) {
        if (o == this) return true;
        if (!(o instanceof DeliveryExecutive)) return false;
        final DeliveryExecutive other = (DeliveryExecutive) o;
        if (!other.canEqual((java.lang.Object) this)) return false;
        if (this.isActive() != other.isActive()) return false;
        final java.lang.Object this$version = this.getVersion();
        final java.lang.Object other$version = other.getVersion();
        if (this$version == null ? other$version != null : !this$version.equals(other$version)) return false;
        final java.lang.Object this$id = this.getId();
        final java.lang.Object other$id = other.getId();
        if (this$id == null ? other$id != null : !this$id.equals(other$id)) return false;
        final java.lang.Object this$fullName = this.getFullName();
        final java.lang.Object other$fullName = other.getFullName();
        if (this$fullName == null ? other$fullName != null : !this$fullName.equals(other$fullName)) return false;
        final java.lang.Object this$phoneNumber = this.getPhoneNumber();
        final java.lang.Object other$phoneNumber = other.getPhoneNumber();
        if (this$phoneNumber == null ? other$phoneNumber != null : !this$phoneNumber.equals(other$phoneNumber)) return false;
        final java.lang.Object this$vehicleNumber = this.getVehicleNumber();
        final java.lang.Object other$vehicleNumber = other.getVehicleNumber();
        if (this$vehicleNumber == null ? other$vehicleNumber != null : !this$vehicleNumber.equals(other$vehicleNumber)) return false;
        final java.lang.Object this$photoUrl = this.getPhotoUrl();
        final java.lang.Object other$photoUrl = other.getPhotoUrl();
        if (this$photoUrl == null ? other$photoUrl != null : !this$photoUrl.equals(other$photoUrl)) return false;
        final java.lang.Object this$email = this.getEmail();
        final java.lang.Object other$email = other.getEmail();
        if (this$email == null ? other$email != null : !this$email.equals(other$email)) return false;
        final java.lang.Object this$status = this.getStatus();
        final java.lang.Object other$status = other.getStatus();
        if (this$status == null ? other$status != null : !this$status.equals(other$status)) return false;
        final java.lang.Object this$verificationStatus = this.getVerificationStatus();
        final java.lang.Object other$verificationStatus = other.getVerificationStatus();
        if (this$verificationStatus == null ? other$verificationStatus != null : !this$verificationStatus.equals(other$verificationStatus)) return false;
        final java.lang.Object this$vehicleType = this.getVehicleType();
        final java.lang.Object other$vehicleType = other.getVehicleType();
        if (this$vehicleType == null ? other$vehicleType != null : !this$vehicleType.equals(other$vehicleType)) return false;
        final java.lang.Object this$lastBiometricVerificationAt = this.getLastBiometricVerificationAt();
        final java.lang.Object other$lastBiometricVerificationAt = other.getLastBiometricVerificationAt();
        if (this$lastBiometricVerificationAt == null ? other$lastBiometricVerificationAt != null : !this$lastBiometricVerificationAt.equals(other$lastBiometricVerificationAt)) return false;
        final java.lang.Object this$lastKnownLocation = this.getLastKnownLocation();
        final java.lang.Object other$lastKnownLocation = other.getLastKnownLocation();
        if (this$lastKnownLocation == null ? other$lastKnownLocation != null : !this$lastKnownLocation.equals(other$lastKnownLocation)) return false;
        final java.lang.Object this$createdAt = this.getCreatedAt();
        final java.lang.Object other$createdAt = other.getCreatedAt();
        if (this$createdAt == null ? other$createdAt != null : !this$createdAt.equals(other$createdAt)) return false;
        final java.lang.Object this$updatedAt = this.getUpdatedAt();
        final java.lang.Object other$updatedAt = other.getUpdatedAt();
        if (this$updatedAt == null ? other$updatedAt != null : !this$updatedAt.equals(other$updatedAt)) return false;
        return true;
    }

protected boolean canEqual(final java.lang.Object other) {
        return other instanceof DeliveryExecutive;
    }

    @java.lang.Override
public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        result = result * PRIME + (this.isActive() ? 79 : 97);
        final java.lang.Object $version = this.getVersion();
        result = result * PRIME + ($version == null ? 43 : $version.hashCode());
        final java.lang.Object $id = this.getId();
        result = result * PRIME + ($id == null ? 43 : $id.hashCode());
        final java.lang.Object $fullName = this.getFullName();
        result = result * PRIME + ($fullName == null ? 43 : $fullName.hashCode());
        final java.lang.Object $phoneNumber = this.getPhoneNumber();
        result = result * PRIME + ($phoneNumber == null ? 43 : $phoneNumber.hashCode());
        final java.lang.Object $vehicleNumber = this.getVehicleNumber();
        result = result * PRIME + ($vehicleNumber == null ? 43 : $vehicleNumber.hashCode());
        final java.lang.Object $photoUrl = this.getPhotoUrl();
        result = result * PRIME + ($photoUrl == null ? 43 : $photoUrl.hashCode());
        final java.lang.Object $email = this.getEmail();
        result = result * PRIME + ($email == null ? 43 : $email.hashCode());
        final java.lang.Object $status = this.getStatus();
        result = result * PRIME + ($status == null ? 43 : $status.hashCode());
        final java.lang.Object $verificationStatus = this.getVerificationStatus();
        result = result * PRIME + ($verificationStatus == null ? 43 : $verificationStatus.hashCode());
        final java.lang.Object $vehicleType = this.getVehicleType();
        result = result * PRIME + ($vehicleType == null ? 43 : $vehicleType.hashCode());
        final java.lang.Object $lastBiometricVerificationAt = this.getLastBiometricVerificationAt();
        result = result * PRIME + ($lastBiometricVerificationAt == null ? 43 : $lastBiometricVerificationAt.hashCode());
        final java.lang.Object $lastKnownLocation = this.getLastKnownLocation();
        result = result * PRIME + ($lastKnownLocation == null ? 43 : $lastKnownLocation.hashCode());
        final java.lang.Object $createdAt = this.getCreatedAt();
        result = result * PRIME + ($createdAt == null ? 43 : $createdAt.hashCode());
        final java.lang.Object $updatedAt = this.getUpdatedAt();
        result = result * PRIME + ($updatedAt == null ? 43 : $updatedAt.hashCode());
        return result;
    }

    @java.lang.Override
public java.lang.String toString() {
        return "DeliveryExecutive(id=" + this.getId() + ", fullName=" + this.getFullName() + ", phoneNumber=" + this.getPhoneNumber() + ", vehicleNumber=" + this.getVehicleNumber() + ", photoUrl=" + this.getPhotoUrl() + ", email=" + this.getEmail() + ", status=" + this.getStatus() + ", verificationStatus=" + this.getVerificationStatus() + ", vehicleType=" + this.getVehicleType() + ", active=" + this.isActive() + ", lastBiometricVerificationAt=" + this.getLastBiometricVerificationAt() + ", version=" + this.getVersion() + ", lastKnownLocation=" + this.getLastKnownLocation() + ", createdAt=" + this.getCreatedAt() + ", updatedAt=" + this.getUpdatedAt() + ")";
    }

public DeliveryExecutive() {
    }
}
