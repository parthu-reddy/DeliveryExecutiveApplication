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
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/delivery/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY')")
@Slf4j
public class DeliveryOrderController {

    private final CustomerServiceClient customerServiceClient;
    private final com.fooddelivery.delivery.service.OrderAssignmentService orderAssignmentService;

    @GetMapping("/active")
    public ResponseEntity<List<JsonNode>> getActiveOrders(Principal principal) {
        UUID driverId = UUID.fromString(principal.getName());
        List<JsonNode> orders = customerServiceClient.getActiveOrdersForDriver(driverId);
        return ResponseEntity.ok(mapToUiOrders(orders));
    }

    @GetMapping("/available")
    public ResponseEntity<List<JsonNode>> getAvailableOrders(Principal principal) {
        UUID driverId = UUID.fromString(principal.getName());
        String pendingOrderId = orderAssignmentService.getPendingPing(driverId);
        if (pendingOrderId == null) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }

        List<JsonNode> orders = customerServiceClient.getUnassignedOrders();
        List<JsonNode> matchingOrders = orders.stream()
                .filter(order -> {
                    JsonNode idNode = order.get("id");
                    return idNode != null && pendingOrderId.equals(idNode.asText());
                })
                .collect(java.util.stream.Collectors.toList());
                
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
                    
                    // Extract dynamic payout from charges where payeeType == DRIVER
                    if (obj.has("charges") && obj.get("charges").isArray()) {
                        double totalPayout = 0.0;
                        for (JsonNode charge : obj.get("charges")) {
                            if (charge.has("payeeType") && "DRIVER".equals(charge.get("payeeType").asText())
                                && charge.has("amount")) {
                                totalPayout += charge.get("amount").asDouble();
                            }
                        }
                        if (totalPayout > 0) {
                            obj.put("payout", totalPayout);
                        }
                        // SECURITY: Strip full financial ledger — driver should only see their payout
                        obj.remove("charges");
                    }
                }
            });
        }
        return orders;
    }
}
