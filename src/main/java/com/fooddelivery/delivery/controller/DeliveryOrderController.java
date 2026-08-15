package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.client.CustomerServiceClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/delivery/orders")
@PreAuthorize("hasRole(\'DELIVERY\')")
@lombok.extern.slf4j.Slf4j
public class DeliveryOrderController {
    @java.lang.SuppressWarnings("all")

    private final CustomerServiceClient customerServiceClient;
    private final com.fooddelivery.delivery.service.OrderAssignmentService orderAssignmentService;

    @GetMapping("/active")
    public ResponseEntity<JsonNode> getActiveOrders(Principal principal, @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
        UUID driverId = UUID.fromString(principal.getName());
        JsonNode pageNode = customerServiceClient.getActiveOrdersForDriver(driverId, page, size);
        mapToUiOrdersInPage(pageNode);
        return ResponseEntity.ok(pageNode);
    }

    @GetMapping("/available")
    public ResponseEntity<List<JsonNode>> getAvailableOrders(Principal principal) {
        UUID driverId = UUID.fromString(principal.getName());
        String pendingOrderId = orderAssignmentService.getPendingPing(driverId);
        if (pendingOrderId == null) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        List<JsonNode> orders = customerServiceClient.getUnassignedOrders();
        List<JsonNode> matchingOrders = orders.stream().filter(order -> {
            JsonNode idNode = order.get("id");
            return idNode != null && pendingOrderId.equals(idNode.asText());
        }).collect(java.util.stream.Collectors.toList());
        // Add remainingPingSeconds
        Long timeoutAt = orderAssignmentService.getPingExpiration(UUID.fromString(pendingOrderId));
        if (timeoutAt != null) {
            long remaining = (timeoutAt - System.currentTimeMillis()) / 1000;
            if (remaining < 0) remaining = 0;
            for (JsonNode node : matchingOrders) {
                if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("remainingPingSeconds", remaining);
                }
            }
        }
        return ResponseEntity.ok(mapToUiOrders(matchingOrders));
    }

    @GetMapping("/history")
    public ResponseEntity<JsonNode> getOrderHistory(Principal principal, @RequestParam(value = "date", required = false) String date, @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
        UUID driverId = UUID.fromString(principal.getName());
        JsonNode pageNode = customerServiceClient.getOrderHistoryForDriver(driverId, date, page, size);
        mapToUiOrdersInPage(pageNode);
        return ResponseEntity.ok(pageNode);
    }

    private List<JsonNode> mapToUiOrders(List<JsonNode> orders) {
        if (orders != null) {
            orders.forEach(order -> {
                if (order instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                    com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) order;
                    if (obj.has("deliveryExecutiveId")) {
                        obj.set("riderId", obj.get("deliveryExecutiveId"));
                    }
                    // Extract dynamic payout from the top-level entity fields (which are now denormalized)
                    double totalPayout = obj.has("driverGrossPayout") ? obj.get("driverGrossPayout").asDouble() : 0.0;
                    double driverTaxes = obj.has("driverTaxes") ? obj.get("driverTaxes").asDouble() : 0.0;
                    double payout = obj.has("driverNetPayout") ? obj.get("driverNetPayout").asDouble() : 0.0;
                    double customerContribution = obj.has("deliveryFee") ? obj.get("deliveryFee").asDouble() : 0.0;
                    double restaurantContribution = obj.has("restaurantDeliveryContribution") ? obj.get("restaurantDeliveryContribution").asDouble() : 0.0;
                    
                    if (totalPayout > 0) {
                        obj.put("grossPayout", totalPayout);
                        obj.put("driverTaxes", driverTaxes);
                        obj.put("payout", payout);
                        obj.put("driverCustomerContribution", customerContribution);
                        obj.put("driverRestaurantContribution", restaurantContribution);
                        // Optional tip field if it gets added in the future
                        if (obj.has("driverTip")) {
                            obj.put("driverTip", obj.get("driverTip").asDouble());
                        }
                    }
                    // SECURITY: Strip full financial ledger — driver should only see their payout
                    if (obj.has("charges")) {
                        obj.remove("charges");
                    }
                }
            });
        }
        return orders;
    }

    private void mapToUiOrdersInPage(JsonNode pageNode) {
        if (pageNode != null && pageNode.has("content") && pageNode.get("content").isArray()) {
            java.util.List<JsonNode> items = new java.util.ArrayList<>();
            for (JsonNode n : pageNode.get("content")) { items.add(n); }
            mapToUiOrders(items);
        }
    }

    @java.lang.SuppressWarnings("all")
    public DeliveryOrderController(final CustomerServiceClient customerServiceClient, final com.fooddelivery.delivery.service.OrderAssignmentService orderAssignmentService) {
        this.customerServiceClient = customerServiceClient;
        this.orderAssignmentService = orderAssignmentService;
    }
}
