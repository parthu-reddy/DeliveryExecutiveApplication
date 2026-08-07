package com.fooddelivery.delivery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class TelemetryEventRequest {
    @NotBlank(message = "driverId is required")
    @Size(max = 36)
    @Pattern(regexp = "^[0-9a-fA-F\\-]{36}$")
    private String driverId;
    @NotNull(message = "lat is required")
    private Double lat;
    @NotNull(message = "lng is required")
    private Double lng;
    @Size(max = 36)
    @Pattern(regexp = "^[0-9a-fA-F\\-]{36}$")
    private String orderId;
    private Double speedKmh;
    private Boolean isMockLocation;
    private Long timestampMs;

    @java.lang.SuppressWarnings("all")
    public TelemetryEventRequest() {
    }

    @java.lang.SuppressWarnings("all")
    public String getDriverId() {
        return this.driverId;
    }

    @java.lang.SuppressWarnings("all")
    public Double getLat() {
        return this.lat;
    }

    @java.lang.SuppressWarnings("all")
    public Double getLng() {
        return this.lng;
    }

    @java.lang.SuppressWarnings("all")
    public String getOrderId() {
        return this.orderId;
    }

    @java.lang.SuppressWarnings("all")
    public Double getSpeedKmh() {
        return this.speedKmh;
    }

    @java.lang.SuppressWarnings("all")
    public Boolean getIsMockLocation() {
        return this.isMockLocation;
    }

    @java.lang.SuppressWarnings("all")
    public Long getTimestampMs() {
        return this.timestampMs;
    }

    @java.lang.SuppressWarnings("all")
    public void setDriverId(final String driverId) {
        this.driverId = driverId;
    }

    @java.lang.SuppressWarnings("all")
    public void setLat(final Double lat) {
        this.lat = lat;
    }

    @java.lang.SuppressWarnings("all")
    public void setLng(final Double lng) {
        this.lng = lng;
    }

    @java.lang.SuppressWarnings("all")
    public void setOrderId(final String orderId) {
        this.orderId = orderId;
    }

    @java.lang.SuppressWarnings("all")
    public void setSpeedKmh(final Double speedKmh) {
        this.speedKmh = speedKmh;
    }

    @java.lang.SuppressWarnings("all")
    public void setIsMockLocation(final Boolean isMockLocation) {
        this.isMockLocation = isMockLocation;
    }

    @java.lang.SuppressWarnings("all")
    public void setTimestampMs(final Long timestampMs) {
        this.timestampMs = timestampMs;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("all")
    public boolean equals(final java.lang.Object o) {
        if (o == this) return true;
        if (!(o instanceof TelemetryEventRequest)) return false;
        final TelemetryEventRequest other = (TelemetryEventRequest) o;
        if (!other.canEqual((java.lang.Object) this)) return false;
        final java.lang.Object this$lat = this.getLat();
        final java.lang.Object other$lat = other.getLat();
        if (this$lat == null ? other$lat != null : !this$lat.equals(other$lat)) return false;
        final java.lang.Object this$lng = this.getLng();
        final java.lang.Object other$lng = other.getLng();
        if (this$lng == null ? other$lng != null : !this$lng.equals(other$lng)) return false;
        final java.lang.Object this$speedKmh = this.getSpeedKmh();
        final java.lang.Object other$speedKmh = other.getSpeedKmh();
        if (this$speedKmh == null ? other$speedKmh != null : !this$speedKmh.equals(other$speedKmh)) return false;
        final java.lang.Object this$isMockLocation = this.getIsMockLocation();
        final java.lang.Object other$isMockLocation = other.getIsMockLocation();
        if (this$isMockLocation == null ? other$isMockLocation != null : !this$isMockLocation.equals(other$isMockLocation)) return false;
        final java.lang.Object this$timestampMs = this.getTimestampMs();
        final java.lang.Object other$timestampMs = other.getTimestampMs();
        if (this$timestampMs == null ? other$timestampMs != null : !this$timestampMs.equals(other$timestampMs)) return false;
        final java.lang.Object this$driverId = this.getDriverId();
        final java.lang.Object other$driverId = other.getDriverId();
        if (this$driverId == null ? other$driverId != null : !this$driverId.equals(other$driverId)) return false;
        final java.lang.Object this$orderId = this.getOrderId();
        final java.lang.Object other$orderId = other.getOrderId();
        if (this$orderId == null ? other$orderId != null : !this$orderId.equals(other$orderId)) return false;
        return true;
    }

    @java.lang.SuppressWarnings("all")
    protected boolean canEqual(final java.lang.Object other) {
        return other instanceof TelemetryEventRequest;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("all")
    public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        final java.lang.Object $lat = this.getLat();
        result = result * PRIME + ($lat == null ? 43 : $lat.hashCode());
        final java.lang.Object $lng = this.getLng();
        result = result * PRIME + ($lng == null ? 43 : $lng.hashCode());
        final java.lang.Object $speedKmh = this.getSpeedKmh();
        result = result * PRIME + ($speedKmh == null ? 43 : $speedKmh.hashCode());
        final java.lang.Object $isMockLocation = this.getIsMockLocation();
        result = result * PRIME + ($isMockLocation == null ? 43 : $isMockLocation.hashCode());
        final java.lang.Object $timestampMs = this.getTimestampMs();
        result = result * PRIME + ($timestampMs == null ? 43 : $timestampMs.hashCode());
        final java.lang.Object $driverId = this.getDriverId();
        result = result * PRIME + ($driverId == null ? 43 : $driverId.hashCode());
        final java.lang.Object $orderId = this.getOrderId();
        result = result * PRIME + ($orderId == null ? 43 : $orderId.hashCode());
        return result;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("all")
    public java.lang.String toString() {
        return "TelemetryEventRequest(driverId=" + this.getDriverId() + ", lat=" + this.getLat() + ", lng=" + this.getLng() + ", orderId=" + this.getOrderId() + ", speedKmh=" + this.getSpeedKmh() + ", isMockLocation=" + this.getIsMockLocation() + ", timestampMs=" + this.getTimestampMs() + ")";
    }
}
