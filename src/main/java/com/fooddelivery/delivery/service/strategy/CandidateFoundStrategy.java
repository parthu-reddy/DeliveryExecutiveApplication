package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.common.constants.EventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class CandidateFoundStrategy implements DeliveryEventStrategy {

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        UUID orderId = UUID.fromString(root.path("orderId").asText());
        String driverId = root.path("driverId").asText(null);
        log.info("Delivery Application received DISPATCH_CANDIDATE_FOUND for order {}. Driver {} will be pinged.", orderId, driverId);
        // Here we would typically push a notification to the driver's app over WebSockets.
        // For now, we simulate this by logging the action.
        log.info("Pinging Driver {} for Order {}...", driverId, orderId);
    }

    @Override
    public List<String> getEventTypes() {
        return Collections.singletonList(EventType.DISPATCH_CANDIDATE_FOUND);
    }
}
