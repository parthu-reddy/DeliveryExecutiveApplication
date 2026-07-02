package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;

public interface DeliveryEventStrategy {
    void process(JsonNode root, String eventType) throws Exception;
    java.util.List<String> getEventTypes();
}
