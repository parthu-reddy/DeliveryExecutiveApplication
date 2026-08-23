package com.fooddelivery.delivery.dto;

import java.util.UUID;

public class DriverLocationDTO {
    private UUID id;
    private String fullName;
    private String phoneNumber;
    private Double lat;
    private Double lng;
    private String status;

public UUID getId() {
        return this.id;
    }

public String getFullName() {
        return this.fullName;
    }

public String getPhoneNumber() {
        return this.phoneNumber;
    }

public Double getLat() {
        return this.lat;
    }

public Double getLng() {
        return this.lng;
    }

public String getStatus() {
        return this.status;
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

public void setLat(final Double lat) {
        this.lat = lat;
    }

public void setLng(final Double lng) {
        this.lng = lng;
    }

public void setStatus(final String status) {
        this.status = status;
    }

    @java.lang.Override
public boolean equals(final java.lang.Object o) {
        if (o == this) return true;
        if (!(o instanceof DriverLocationDTO)) return false;
        final DriverLocationDTO other = (DriverLocationDTO) o;
        if (!other.canEqual((java.lang.Object) this)) return false;
        final java.lang.Object this$lat = this.getLat();
        final java.lang.Object other$lat = other.getLat();
        if (this$lat == null ? other$lat != null : !this$lat.equals(other$lat)) return false;
        final java.lang.Object this$lng = this.getLng();
        final java.lang.Object other$lng = other.getLng();
        if (this$lng == null ? other$lng != null : !this$lng.equals(other$lng)) return false;
        final java.lang.Object this$id = this.getId();
        final java.lang.Object other$id = other.getId();
        if (this$id == null ? other$id != null : !this$id.equals(other$id)) return false;
        final java.lang.Object this$fullName = this.getFullName();
        final java.lang.Object other$fullName = other.getFullName();
        if (this$fullName == null ? other$fullName != null : !this$fullName.equals(other$fullName)) return false;
        final java.lang.Object this$phoneNumber = this.getPhoneNumber();
        final java.lang.Object other$phoneNumber = other.getPhoneNumber();
        if (this$phoneNumber == null ? other$phoneNumber != null : !this$phoneNumber.equals(other$phoneNumber)) return false;
        final java.lang.Object this$status = this.getStatus();
        final java.lang.Object other$status = other.getStatus();
        if (this$status == null ? other$status != null : !this$status.equals(other$status)) return false;
        return true;
    }

protected boolean canEqual(final java.lang.Object other) {
        return other instanceof DriverLocationDTO;
    }

    @java.lang.Override
public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        final java.lang.Object $lat = this.getLat();
        result = result * PRIME + ($lat == null ? 43 : $lat.hashCode());
        final java.lang.Object $lng = this.getLng();
        result = result * PRIME + ($lng == null ? 43 : $lng.hashCode());
        final java.lang.Object $id = this.getId();
        result = result * PRIME + ($id == null ? 43 : $id.hashCode());
        final java.lang.Object $fullName = this.getFullName();
        result = result * PRIME + ($fullName == null ? 43 : $fullName.hashCode());
        final java.lang.Object $phoneNumber = this.getPhoneNumber();
        result = result * PRIME + ($phoneNumber == null ? 43 : $phoneNumber.hashCode());
        final java.lang.Object $status = this.getStatus();
        result = result * PRIME + ($status == null ? 43 : $status.hashCode());
        return result;
    }

    @java.lang.Override
public java.lang.String toString() {
        return "DriverLocationDTO(id=" + this.getId() + ", fullName=" + this.getFullName() + ", phoneNumber=" + this.getPhoneNumber() + ", lat=" + this.getLat() + ", lng=" + this.getLng() + ", status=" + this.getStatus() + ")";
    }

public DriverLocationDTO() {
    }

public DriverLocationDTO(final UUID id, final String fullName, final String phoneNumber, final Double lat, final Double lng, final String status) {
        this.id = id;
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.lat = lat;
        this.lng = lng;
        this.status = status;
    }
}
