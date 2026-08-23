package com.fooddelivery.delivery.dto;

import java.util.UUID;

@lombok.Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class DriverLocationDTO {
    private UUID id;
    private String fullName;
    private String phoneNumber;
    private Double lat;
    private Double lng;
    private String status;

















}
