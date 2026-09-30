package com.fooddelivery.delivery.service;

import java.util.Objects;
import java.util.UUID;

/**
 * The only command accepted by the manual assignment executor.
 *
 * <p>It is built from the CustomerApplication outbox event after that service has authenticated
 * the admin and persisted the current operation on the order.
 */
public record ManualForceAssignment(
        UUID orderId,
        UUID driverId,
        String operationId,
        UUID actorId,
        String reason,
        String dispatchCityId) {

    public ManualForceAssignment {
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(driverId, "driverId is required");
        Objects.requireNonNull(actorId, "actorId is required");
        operationId = required(operationId, "operationId");
        reason = required(reason, "reason");
        dispatchCityId = required(dispatchCityId, "dispatchCityId");
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
