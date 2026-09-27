package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes the rider's active-order list reflect every step the delivery service has accepted.
 *
 * <p>The list comes from the customer service, which learns of each step (arrived, picked up,
 * delivered) from this service's outbox event a few seconds after the rider's OTP was accepted.
 * Until then it answered with the step before: a reload after pickup showed the pickup form again,
 * and a delivered order came back as the active contract. The assignment row records the step in
 * the same transaction as the event, and this lays it over the customer service's answer.
 *
 * <p>Forward only, and never over a cancellation: the customer service stays the authority on
 * whether the order is still live.
 */
@Service
@lombok.RequiredArgsConstructor
public class ConfirmedDeliveryProgress {

    private final OrderAssignmentRepository assignmentRepository;

    /** Applies to a Spring {@code Page} JSON ({@code content} array) in place. */
    public void apply(JsonNode page, UUID driverId) {
        if (page == null || !page.has("content") || !page.get("content").isArray()) return;
        List<UUID> ids = new ArrayList<>();
        for (JsonNode order : page.get("content")) {
            UUID id = uuid(order.path("id").asText(null));
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return;
        Map<UUID, OrderAssignment> assignments = new HashMap<>();
        assignmentRepository.findAllById(ids).forEach(a -> assignments.put(a.getOrderId(), a));
        for (JsonNode order : page.get("content")) {
            OrderAssignment a = assignments.get(uuid(order.path("id").asText(null)));
            if (a == null || a.getDeliveryStatus() == null || !driverId.equals(a.getDriverId())) continue;
            if (isCancelled(order.path("status").asText(""))) continue;
            DeliveryStatus reported = parse(order.path("deliveryStatus").asText(null));
            if (reported == null || a.getDeliveryStatus().getSequence() > reported.getSequence()) {
                ((ObjectNode) order).put("deliveryStatus", a.getDeliveryStatus().name());
            }
        }
    }

    private static boolean isCancelled(String status) {
        return status.startsWith("CANCELLED") || status.startsWith("REJECTED");
    }

    private static DeliveryStatus parse(String s) {
        try {
            return s == null ? null : DeliveryStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
