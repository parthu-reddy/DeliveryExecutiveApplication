package com.fooddelivery.delivery.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
public class TelemetryEventRequest {
    @NotBlank(message = "driverId is required")
    private String driverId;

    @NotNull(message = "lat is required")
    private Double lat;

    @NotNull(message = "lng is required")
    private Double lng;

    private String orderId;
}
