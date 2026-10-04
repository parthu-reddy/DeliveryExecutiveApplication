package com.fooddelivery.delivery.dto;
import com.fooddelivery.common.enums.VehicleClass;
import jakarta.validation.constraints.*;
public record DeliveryApplicationDraftRequest(@NotBlank @Size(max=100) String fullName,
        @Size(max=50) String vehicleNumber, VehicleClass vehicleType, @Size(max=64) String cityId,
        @Size(max=512) String photoUrl, @PositiveOrZero Integer version) {
    @Override public String toString() { return "DeliveryApplicationDraftRequest[version=" + version + "]"; }
}
