package com.fooddelivery.delivery.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

@FeignClient(name = "customer-service", contextId = "customerServiceClient")
public interface CustomerServiceClient {

    @GetMapping("/api/v1/internal/orders/driver/{driverId}/active")
    List<JsonNode> getActiveOrdersForDriver(@PathVariable("driverId") UUID driverId);

    @GetMapping("/api/v1/internal/orders/driver/{driverId}/history")
    List<JsonNode> getOrderHistoryForDriver(@PathVariable("driverId") UUID driverId, @RequestParam(value = "date", required = false) String date);

    @GetMapping("/api/v1/internal/orders/unassigned")
    List<JsonNode> getUnassignedOrders();
}
