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
@PreAuthorize("hasRole('DELIVERY')")
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class DeliveryOrderController {
private final CustomerServiceClient customerServiceClient;
    private final com.fooddelivery.delivery.service.OrderAssignmentService orderAssignmentService;

    @GetMapping("/active")
    public ResponseEntity<JsonNode> getActiveOrders(Principal principal, @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
        UUID driverId = UUID.fromString(principal.getName());
        JsonNode pageNode = customerServiceClient.getActiveOrdersForDriver(driverId, page, size);
        mapToUiOrdersInPage(pageNode, driverId);
        return ResponseEntity.ok(pageNode);
    }

    @GetMapping("/available")
    public ResponseEntity<List<JsonNode>> getAvailableOrders(Principal principal) {
        UUID driverId = UUID.fromString(principal.getName());
        String pendingOrderId = orderAssignmentService.getPendingPing(driverId);
        if (pendingOrderId == null) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        // Never offer a ping whose window has closed. This is the endpoint the rider app actually
        // polls, so it decides what prompt appears; ACCEPT_SCRIPT will refuse anything past the
        // deadline, and a prompt the rider cannot act on reads as the app ignoring them.
        //
        // Both branches matter. A missing ZSET entry means the timeout poller already cleaned the
        // ping up, and returning the order anyway sent no remainingPingSeconds at all -- so the app
        // fell back to a fresh 60-second countdown for an order that could never be accepted. The
        // driver:pending_ping key outlives the window deliberately (see CandidateFoundStrategy),
        // so its presence alone proves nothing about whether the ping is still live.
        Long timeoutAt = orderAssignmentService.getPingExpiration(UUID.fromString(pendingOrderId));
        if (timeoutAt == null || timeoutAt <= System.currentTimeMillis()) {
            log.info("PING_WINDOW_CLOSED driverId={} orderId={} timeoutAt={} -- not offering it",
                    driverId, pendingOrderId, timeoutAt);
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        List<JsonNode> orders = customerServiceClient.getUnassignedOrders();
        List<JsonNode> matchingOrders = orders.stream().filter(order -> {
            JsonNode idNode = order.get("id");
            return idNode != null && pendingOrderId.equals(idNode.asText());
        }).collect(java.util.stream.Collectors.toList());
        long remaining = (timeoutAt - System.currentTimeMillis()) / 1000;
        if (remaining < 0) remaining = 0;
        for (JsonNode node : matchingOrders) {
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("remainingPingSeconds", remaining);
            }
        }
        return ResponseEntity.ok(mapToUiOrders(matchingOrders, driverId));
    }

    @GetMapping("/history")
    public ResponseEntity<JsonNode> getOrderHistory(Principal principal,
            // The rider's day as [from, to) instants, computed in the rider's browser: whose "today" it
            // is gets decided where the rider is, not on a server. TimezoneCorrectness_2026-09-25.
            @RequestParam("from") java.time.Instant from, @RequestParam("to") java.time.Instant to,
            @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
        UUID driverId = UUID.fromString(principal.getName());
        JsonNode pageNode = customerServiceClient.getOrderHistoryForDriver(driverId, from, to, page, size);
        mapToUiOrdersInPage(pageNode, driverId);
        return ResponseEntity.ok(pageNode);
    }

    private List<JsonNode> mapToUiOrders(List<JsonNode> orders, UUID driverId) {
        if (orders != null && !orders.isEmpty()) {
            java.util.List<String> orderIds = new java.util.ArrayList<>();
            orders.forEach(order -> {
                if (order.has("id")) {
                    orderIds.add(order.get("id").asText());
                }
            });

            java.util.Map<String, com.fooddelivery.common.dto.order.DriverOrderEarnings> earningsMap = new java.util.HashMap<>();
            if (!orderIds.isEmpty()) {
                try {
                    List<com.fooddelivery.common.dto.order.DriverOrderEarnings> batch = customerServiceClient.getDriverOrderMoneyBatch(driverId, orderIds);
                    if (batch != null) {
                        for (com.fooddelivery.common.dto.order.DriverOrderEarnings e : batch) {
                            earningsMap.put(e.getOrderId().toString(), e);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to fetch earnings batch for orders", e);
                }
            }

            orders.forEach(order -> {
                if (order instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                    com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) order;
                    if (obj.has("deliveryExecutiveId")) {
                        obj.set("riderId", obj.get("deliveryExecutiveId"));
                    }

                    // Replace money fields with batch output
                    if (obj.has("id")) {
                        String id = obj.get("id").asText();
                        com.fooddelivery.common.dto.order.DriverOrderEarnings earnings = earningsMap.get(id);
                        if (earnings != null) {
                            obj.putPOJO("earnings", earnings);
                        }
                    }

                    // SECURITY: Strip full financial ledger — driver should only see their payout
                    java.util.List<String> fieldsToRemove = java.util.List.of(
                            "charges",
                            "restaurantPayout",
                            "restaurantPlatformFee",
                            "restaurantDeliveryContribution",
                            "customerPlatformFee",
                            "platformBonus",
                            "sgst",
                            "cgst"
                    );
                    obj.remove(fieldsToRemove);
                }
            });
        }
        return orders;
    }

    private void mapToUiOrdersInPage(JsonNode pageNode, UUID driverId) {
        if (pageNode != null && pageNode.has("content") && pageNode.get("content").isArray()) {
            java.util.List<JsonNode> items = new java.util.ArrayList<>();
            for (JsonNode n : pageNode.get("content")) { items.add(n); }
            mapToUiOrders(items, driverId);
        }
    }

}
