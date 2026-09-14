package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class ForceAssignDriverStrategy implements DeliveryEventStrategy<com.fooddelivery.common.event.ForceAssignDriverEvent> {
private final OrderAssignmentService orderAssignmentService;

    @Override
    public Class<com.fooddelivery.common.event.ForceAssignDriverEvent> eventClass() {
        return com.fooddelivery.common.event.ForceAssignDriverEvent.class;
    }

    @Override
    public void handle(com.fooddelivery.common.event.ForceAssignDriverEvent event, String eventType) throws Exception {
        UUID orderId = event.orderUuid();
        UUID driverId = event.getDriverId() != null ? java.util.UUID.fromString(event.getDriverId()) : null;
        if (driverId == null) {
            log.error("Received FORCE_ASSIGN_DRIVER for order {}, but no driverId was provided in payload", orderId);
            return;
        }
        log.info("Received FORCE_ASSIGN_DRIVER event. Forcing assignment of order {} to driver {}", orderId, driverId);
        try {
            orderAssignmentService.forceAssignOrder(orderId, driverId);
            log.info("Successfully completed force assignment for order {} to driver {}", orderId, driverId);
        } catch (Exception e) {
            log.error("Failed to execute force assignment for order {} to driver {}", orderId, driverId, e);
            throw e; // Let Kafka re-try or DLQ it if needed
        }
    }

    @Override
    public List<String> getEventTypes() {
        return Collections.singletonList(EventType.FORCE_ASSIGN_DRIVER.name());
    }

}
