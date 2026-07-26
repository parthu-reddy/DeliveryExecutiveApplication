package com.fooddelivery.delivery.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Data
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
}
