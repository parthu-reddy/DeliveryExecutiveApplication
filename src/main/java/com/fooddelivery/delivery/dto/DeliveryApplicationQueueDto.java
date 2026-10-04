package com.fooddelivery.delivery.dto;
import com.fooddelivery.common.enums.ApplicationStatus;
import java.time.Instant;
import java.util.UUID;
public record DeliveryApplicationQueueDto(UUID executiveId, String fullName, String cityId, ApplicationStatus status,
        Instant submittedAt, Integer version) { }
