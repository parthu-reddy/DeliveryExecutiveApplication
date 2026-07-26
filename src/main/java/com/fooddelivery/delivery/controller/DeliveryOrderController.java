package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.client.CustomerServiceClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/delivery/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY')")
public class DeliveryOrderController {

    private final CustomerServiceClient customerServiceClient;

    @GetMapping("/active")
    public ResponseEntity<List<JsonNode>> getActiveOrders(Principal principal) {
        UUID driverId = UUID.fromString(principal.getName());
        List<JsonNode> orders = customerServiceClient.getActiveOrdersForDriver(driverId);
        return ResponseEntity.ok(mapToUiOrders(orders));
    }

    @GetMapping("/available")
    public ResponseEntity<List<JsonNode>> getAvailableOrders() {
        List<JsonNode> orders = customerServiceClient.getUnassignedOrders();
        return ResponseEntity.ok(mapToUiOrders(orders));
    }

    @GetMapping("/history")
    public ResponseEntity<List<JsonNode>> getOrderHistory(Principal principal, @RequestParam(value = "date", required = false) String date) {
        UUID driverId = UUID.fromString(principal.getName());
        List<JsonNode> orders = customerServiceClient.getOrderHistoryForDriver(driverId, date);
        return ResponseEntity.ok(mapToUiOrders(orders));
    }

    private List<JsonNode> mapToUiOrders(List<JsonNode> orders) {
        if (orders != null) {
            orders.forEach(order -> {
                if (order instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                    com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) order;
                    if (obj.has("deliveryExecutiveId")) {
                        obj.set("riderId", obj.get("deliveryExecutiveId"));
                    }
                }
            });
        }
        return orders;
    }
}
