package com.fooddelivery.delivery.dto;

import java.util.UUID;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DriverLocationDTO {
    private UUID id;
    private String fullName;
    private String phoneNumber;
    private Double lat;
    private Double lng;
}
