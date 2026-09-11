package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.AppConstants;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveState;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
public class DeliveredStateStrategy extends AbstractDeliveryOrderState {
    public DeliveredStateStrategy(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                 com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                 org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                 com.fooddelivery.common.outbox.repository.OutboxEventRepository outboxEventRepository,
                 com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository repository,
                 com.fooddelivery.delivery.service.LogisticsDispatchService logisticsDispatchService,
                 com.fooddelivery.delivery.repository.OrderAssignmentRepository assignmentRepository) {
        super(redisTemplate, objectMapper, transactionTemplate, outboxEventRepository, repository, logisticsDispatchService, assignmentRepository);
    }


    @Override
    public DeliveryStatus getSupportedStatus() {
        return DeliveryStatus.DELIVERED;
    }

    @Override
    protected com.fooddelivery.common.constants.EventType getEventType() {
        return EventType.ORDER_DELIVERED;
    }

    /**
     * The delivery OTP comes from the assignment row, not from the cached dispatch payload.
     *
     * <p>The payload key has a 24-hour TTL. When it expired the rider got "Invalid Delivery OTP.
     * Order payload not found." and the order could never be completed by anyone, by any route.
     */
    @Override
    protected void validate(OrderAssignment assignment, String pickupOtp, String deliveryOtp,
                            java.math.BigDecimal cashCollectedAmount) {
        // A cash delivery ends with the rider handing over money. Accepting the status update
        // without the amount leaves the customer service booking a collection for a number nobody
        // declared -- which is exactly what it used to do, using the order total as a stand-in.
        if (assignment.isCashOnDelivery() && cashCollectedAmount == null) {
            throw new IllegalArgumentException("Declare the cash you collected for this order.");
        }
        if (cashCollectedAmount != null && cashCollectedAmount.signum() < 0) {
            throw new IllegalArgumentException("Cash collected cannot be negative.");
        }
        requireOtp(assignment.getDeliveryOtp(), deliveryOtp, "delivery");
    }

    @Override
    protected void updateExecutiveState(UUID driverId, Boolean goOfflineAfter) {
        DeliveryExecutive executive = repository.findLockedById(driverId).orElseThrow(() -> new IllegalArgumentException("Driver not found"));
        DeliveryExecutiveState state = DeliveryExecutiveStateFactory.getState(executive.getStatus());
        state.completeDelivery(executive);
        if (Boolean.TRUE.equals(goOfflineAfter)) {
            log.info("Driver {} opted to go offline after delivery", driverId);
            executive.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.OFFLINE);
        }
        // updatedAt is auto-managed by @UpdateTimestamp
        repository.save(executive);
        redisTemplate.opsForHash().put("drivers:status", driverId.toString(), executive.getStatus().name());
    }

    @Override
    protected void postProcess(UUID driverId, UUID orderId, Boolean goOfflineAfter) {
        releaseAssignment(orderId);
        try {
            logisticsDispatchService.releaseDriverLock(driverId.toString());
        } catch (Exception e) {
            log.error("Failed to release driver lock for driver {} after delivery, will be retried by availability poller", driverId, e);
        }
        redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
        redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId);
        redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId);
        redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderId);
        redisTemplate.opsForZSet().remove(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString());
        redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId, getSupportedStatus().name(), Duration.ofHours(24));
        if (!Boolean.TRUE.equals(goOfflineAfter)) {
            try {
                String cityId = repository.findById(driverId).map(DeliveryExecutive::getCityId).orElse(null);
                if (cityId != null) {
                    String key = "drivers:available:" + cityId;
                    redisTemplate.opsForSet().add(key, driverId.toString());
                }
            } catch (Exception e) {
                log.error("Failed to add driver {} back to Redis pool", driverId, e);
            }
        } else {
            log.info("Driver {} opted to go offline, skipping addition to available pool", driverId);
        }
    }
}
