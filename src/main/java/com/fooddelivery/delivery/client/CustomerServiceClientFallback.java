package com.fooddelivery.delivery.client;

import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class CustomerServiceClientFallback implements CustomerServiceClient {
    @Override
    public JsonNode getActiveOrdersForDriver(UUID driverId, int page, int size) {
        throw new IllegalStateException("Customer service is currently unavailable.");
    }

    @Override
    public JsonNode getOrderHistoryForDriver(UUID driverId, String date, int page, int size) {
        throw new IllegalStateException("Customer service is currently unavailable.");
    }

    @Override
    public List<JsonNode> getUnassignedOrders() {
        throw new IllegalStateException("Customer service is currently unavailable.");
    }
}
