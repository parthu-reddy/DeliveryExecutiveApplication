package com.fooddelivery.delivery.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FleetTrackingUpdateDto {
    private String driverId;
    private String cityId;
    private Double lat;
    private Double lng;
    private String orderId;
}
