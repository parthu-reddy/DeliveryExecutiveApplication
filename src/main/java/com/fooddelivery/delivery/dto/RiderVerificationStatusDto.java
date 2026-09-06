package com.fooddelivery.delivery.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderVerificationStatusDto {
    private String dlStatus;
    private String rcStatus;
    private String bankStatus;
    private String biometricStatus;
    private boolean fullyVerified;
}
