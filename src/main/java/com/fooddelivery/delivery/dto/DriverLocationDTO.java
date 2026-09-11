package com.fooddelivery.delivery.dto;

import java.util.UUID;

@lombok.Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class DriverLocationDTO {
    @jakarta.validation.constraints.NotNull
    private UUID id;
    private String fullName;
    private String phoneNumber;
    @jakarta.validation.constraints.NotNull
    private Double lat;
    @jakarta.validation.constraints.NotNull
    private Double lng;
    @jakarta.validation.constraints.NotNull
    private String status;
}
