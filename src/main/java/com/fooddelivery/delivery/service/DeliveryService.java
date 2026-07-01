package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.IOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import com.fooddelivery.common.constants.EventType;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {

    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private final IDeliveryExecutiveRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public DeliveryExecutive onboard(String name, String phoneNumber, String vehicleNumber) {
        DeliveryExecutive executive = new DeliveryExecutive();
        executive.setId(UUID.randomUUID());
        executive.setName(name);
        executive.setPhoneNumber(phoneNumber);
        executive.setVehicleNumber(vehicleNumber);
        executive.setStatus(DeliveryExecutiveStatus.OFFLINE);
        executive.setCreatedAt(LocalDateTime.now());
        executive.setUpdatedAt(LocalDateTime.now());
        log.info("Onboarded Delivery Executive: {}", executive.getId());
        return repository.save(executive);
    }

    @Transactional
    public DeliveryExecutive toggleStatus(UUID driverId, boolean isOnline) {
        DeliveryExecutive executive = repository.findById(driverId)
                .orElseThrow(() -> new RuntimeException("Driver not found"));
        
        executive.setStatus(isOnline ? DeliveryExecutiveStatus.ONLINE : DeliveryExecutiveStatus.OFFLINE);
        log.info("Driver {} is now {}", driverId, executive.getStatus());
        
        String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
        if (isOnline) {
            redisTemplate.opsForSet().add(key, driverId.toString());
        } else {
            redisTemplate.opsForSet().remove(key, driverId.toString());
        }
        
        return repository.save(executive);
    }

    private final IOutboxEventRepository outboxEventRepository;
    private final LogisticsDispatchService logisticsDispatchService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private static final String TOPIC = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS;

    @Transactional
    public void acceptOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} attempting to accept order {}", driverId, orderId);
        
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent("order:driver:lock:" + orderId, driverId.toString(), java.time.Duration.ofMinutes(60));
        if (Boolean.FALSE.equals(acquired)) {
            log.warn("Order {} was already accepted by another driver. Driver {} ping rejected.", orderId, driverId);
            throw new IllegalStateException("Order is no longer available.");
        }
        
        // Emitting event to CustomerApplication
        com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("eventType", "DRIVER_ASSIGNED");
        payloadNode.put("orderId", orderId.toString());
        payloadNode.put("driverId", driverId.toString());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadNode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DRIVER_ASSIGNED payload", e);
        }
        
        com.fooddelivery.delivery.entity.OutboxEventEntity outboxEvent = com.fooddelivery.delivery.entity.OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateType(com.fooddelivery.common.constants.AppConstants.AGGREGATE_ORDER)
                .aggregateId(orderId.toString())
                .eventType(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED)
                .payload(payload)
                .createdAt(java.time.LocalDateTime.now())
                .status(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_UNPROCESSED)
                .build();
        outboxEventRepository.save(outboxEvent);
        
        DeliveryExecutive executive = repository.findById(driverId).orElseThrow();
        executive.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);
        repository.save(executive);
        
        log.info("Driver {} accepted order {}. Emitted DRIVER_ASSIGNED event.", driverId, orderId);
    }

    public void rejectOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} rejected order ping {}", driverId, orderId);
        
        transactionTemplate.execute(status -> {
            com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.createObjectNode();
            payloadNode.put("eventType", "ORDER_DRIVER_REJECTED");
            payloadNode.put("orderId", orderId.toString());
            payloadNode.put("driverId", driverId.toString());
            String payload;
            try {
                payload = objectMapper.writeValueAsString(payloadNode);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize ORDER_DRIVER_REJECTED payload", e);
            }
            
            com.fooddelivery.delivery.entity.OutboxEventEntity outboxEvent = com.fooddelivery.delivery.entity.OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(com.fooddelivery.common.constants.AppConstants.AGGREGATE_ORDER)
                    .aggregateId(orderId.toString())
                    .eventType(com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED)
                    .payload(payload)
                    .createdAt(java.time.LocalDateTime.now())
                    .status(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_UNPROCESSED)
                    .build();
            outboxEventRepository.save(outboxEvent);
            return null;
        });
        
        logisticsDispatchService.releaseDriverLock(driverId.toString());
    }

    public void updateOrderStatus(UUID driverId, UUID orderId, String status) {
        log.info("Driver {} updating order {} to {}", driverId, orderId, status);
        
        transactionTemplate.execute(txStatus -> {
            String eventType = "DELIVERED".equals(status) ? EventType.ORDER_DELIVERED : EventType.ORDER_STATUS_UPDATED;
            com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.createObjectNode();
            payloadNode.put("eventType", eventType);
            payloadNode.put("orderId", orderId.toString());
            payloadNode.put("status", status);
            String payload;
            try {
                payload = objectMapper.writeValueAsString(payloadNode);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize status update payload", e);
            }
            
            com.fooddelivery.delivery.entity.OutboxEventEntity outboxEvent = com.fooddelivery.delivery.entity.OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(com.fooddelivery.common.constants.AppConstants.AGGREGATE_ORDER)
                    .aggregateId(orderId.toString())
                    .eventType(eventType)
                    .payload(payload)
                    .createdAt(java.time.LocalDateTime.now())
                    .status(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_UNPROCESSED)
                    .build();
            outboxEventRepository.save(outboxEvent);
            
            if ("DELIVERED".equals(status) || "DELIVERY_FAILED".equals(status)) {
                DeliveryExecutive executive = repository.findById(driverId).orElseThrow();
                executive.setStatus(DeliveryExecutiveStatus.ONLINE);
                executive.setUpdatedAt(java.time.LocalDateTime.now());
                repository.save(executive);
            }
            return null;
        });
        
        if ("DELIVERED".equals(status) || "DELIVERY_FAILED".equals(status)) {
            // Release the Redis driver lock so they can receive new pings
            logisticsDispatchService.releaseDriverLock(driverId.toString());
        }
    }

    public void timeoutDriverPing(UUID driverId, UUID orderId) {
        log.info("Driver {} ping timed out for order {}", driverId, orderId);
        
        transactionTemplate.execute(status -> {
            com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.createObjectNode();
            payloadNode.put("eventType", "ORDER_DRIVER_REJECTED");
            payloadNode.put("orderId", orderId.toString());
            payloadNode.put("driverId", driverId.toString());
            String payload;
            try {
                payload = objectMapper.writeValueAsString(payloadNode);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize ORDER_DRIVER_REJECTED payload", e);
            }
            
            com.fooddelivery.delivery.entity.OutboxEventEntity outboxEvent = com.fooddelivery.delivery.entity.OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(com.fooddelivery.common.constants.AppConstants.AGGREGATE_ORDER)
                    .aggregateId(orderId.toString())
                    .eventType(com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED)
                    .payload(payload)
                    .createdAt(java.time.LocalDateTime.now())
                    .status(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_UNPROCESSED)
                    .build();
            outboxEventRepository.save(outboxEvent);
            return null;
        });
        
        logisticsDispatchService.releaseDriverLock(driverId.toString());
    }
}
