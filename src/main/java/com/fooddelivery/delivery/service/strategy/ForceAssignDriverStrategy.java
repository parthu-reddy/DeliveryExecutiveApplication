package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.delivery.service.ManualForceAssignment;
import com.fooddelivery.delivery.service.ManualForceAssignmentResult;
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
        UUID driverId = requiredUuid(event.getDriverId(), "driverId");
        UUID actorId = requiredUuid(event.getActorId(), "actorId");
        if (orderId == null) {
            throw new IllegalArgumentException("FORCE_ASSIGN_DRIVER is missing orderId.");
        }
        ManualForceAssignment command = new ManualForceAssignment(
                orderId,
                driverId,
                event.getOperationId(),
                actorId,
                event.getReason(),
                event.getDispatchCityId());
        log.info("FORCE_ASSIGN_DRIVER_RECEIVED orderId={} driverId={} actorId={} operationId={}",
                orderId, driverId, actorId, command.operationId());
        try {
            ManualForceAssignmentResult result = orderAssignmentService.forceAssignOrder(command);
            if (result.rejected()) {
                log.warn("FORCE_ASSIGN_DRIVER_REJECTED orderId={} driverId={} operationId={} reasonCode={}",
                        orderId, driverId, command.operationId(), result.failureCode());
            } else if (result.ignoredAsSuperseded()) {
                log.info("FORCE_ASSIGN_DRIVER_IGNORED orderId={} driverId={} operationId={}",
                        orderId, driverId, command.operationId());
            } else {
                log.info("FORCE_ASSIGN_DRIVER_COMPLETED orderId={} driverId={} operationId={} replayed={}",
                        orderId, driverId, command.operationId(), result.replayed());
            }
        } catch (Exception e) {
            log.error("FORCE_ASSIGN_DRIVER_FAILED orderId={} driverId={} operationId={}",
                    orderId, driverId, command.operationId(), e);
            throw e; // Let Kafka re-try or DLQ it if needed
        }
    }

    private UUID requiredUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("FORCE_ASSIGN_DRIVER is missing " + field + ".");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("FORCE_ASSIGN_DRIVER has an invalid " + field + ".", invalid);
        }
    }

    @Override
    public List<String> getEventTypes() {
        return Collections.singletonList(EventType.FORCE_ASSIGN_DRIVER.name());
    }

}
