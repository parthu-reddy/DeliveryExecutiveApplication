package com.fooddelivery.delivery.dto;
import jakarta.validation.constraints.*;
public record DeliveryApplicationDecisionRequest(@NotNull @PositiveOrZero Integer version, @Size(max=500) String reason) { }
