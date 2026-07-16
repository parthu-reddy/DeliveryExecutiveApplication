package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
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
    public DeliveryExecutive onboard(UUID driverId, String fullName, String phoneNumber, String vehicleNumber, String photoUrl) {
        DeliveryExecutive executive = repository.findById(driverId).orElseGet(DeliveryExecutive::new);
        
        if (executive.getId() == null) {
            executive.setId(driverId);
            executive.setStatus(DeliveryExecutiveStatus.OFFLINE);
            executive.setCreatedAt(LocalDateTime.now());
        }
        
        executive.setFullName(fullName);
        executive.setPhoneNumber(phoneNumber);
        executive.setVehicleNumber(vehicleNumber);
        executive.setPhotoUrl(photoUrl);
        executive.setUpdatedAt(LocalDateTime.now());
        
        log.info("Onboarded/Updated Delivery Executive: {}", executive.getId());
        return repository.save(executive);
    }

    public java.util.Optional<DeliveryExecutive> findByPhoneNumber(String phoneNumber) {
        return repository.findByPhoneNumber(phoneNumber);
    }

    public DeliveryExecutive toggleStatus(UUID driverId, boolean isOnline) {
        DeliveryExecutive updated = transactionTemplate.execute(status -> {
            DeliveryExecutive executive = repository.findById(driverId)
                    .orElseThrow(() -> new RuntimeException("Driver not found"));
            
            if (isOnline && (executive.getVehicleNumber() == null || executive.getVehicleNumber().trim().isEmpty())) {
                throw new IllegalArgumentException("Registration incomplete: Please complete registration before going online.");
            }
            
            if (isOnline && executive.getStatus() == DeliveryExecutiveStatus.ONLINE) return executive;
            if (!isOnline && executive.getStatus() == DeliveryExecutiveStatus.OFFLINE) return executive;

            com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
            if (isOnline) {
                state.goOnline(executive);
            } else {
                state.goOffline(executive);
            }
            return repository.save(executive);
        });
        
        String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
        try {
            if (isOnline) {
                redisTemplate.opsForSet().add(key, driverId.toString());
            } else {
                redisTemplate.opsForSet().remove(key, driverId.toString());
            }
        } catch (Exception e) {
            log.error("Failed to sync driver status with Redis for driver {}", driverId, e);
            // Non-fatal, but could cause temporary dispatch mismatch
        }
        
        log.info("Driver {} is now {}", driverId, updated.getStatus());
        return updated;
    }

    private final OutboxEventRepository outboxEventRepository;
    private final LogisticsDispatchService logisticsDispatchService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private static final String TOPIC = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS;

    public void acceptOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} attempting to accept order {}", driverId, orderId);
        
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent("order:driver:lock:" + orderId, driverId.toString(), java.time.Duration.ofMinutes(60));
        if (Boolean.FALSE.equals(acquired)) {
            log.warn("Order {} was already accepted by another driver. Driver {} ping rejected.", orderId, driverId);
            throw new IllegalStateException("Order is no longer available.");
        }
        
        try {
            String currentLock = redisTemplate.opsForValue().get("order:driver:lock:" + orderId);
            if ("CANCELLED".equals(currentLock)) {
                throw new IllegalStateException("Order was cancelled during acceptance.");
            }

            transactionTemplate.executeWithoutResult(status -> {
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
                
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AppConstants.AGGREGATE_ORDER)
                        .aggregateId(orderId.toString())
                        .eventType(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED)
                        .payload(payload)
                        .createdAt(java.time.LocalDateTime.now())
                        .status(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_UNPROCESSED)
                        .build();
                outboxEventRepository.save(outboxEvent);
                
                DeliveryExecutive executive = repository.findLockedById(driverId).orElseThrow();
                com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
                state.acceptOrder(executive);
                repository.save(executive);
            });
            log.info("Driver {} accepted order {}. Emitted DRIVER_ASSIGNED event.", driverId, orderId);
            
            redisTemplate.delete("order:ping:pending:" + orderId);
            redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
            
            try {
                String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
                redisTemplate.opsForSet().remove(key, driverId.toString());
            } catch (Exception e) {
                log.error("Failed to remove driver {} from Redis pool", driverId, e);
            }
        } catch (Exception e) {
            log.error("Failed to commit DRIVER_ASSIGNED transaction. Releasing Redis lock for order {}", orderId, e);
            redisTemplate.delete("order:driver:lock:" + orderId);
            redisTemplate.delete("order:ping:pending:" + orderId);
            redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
            throw e;
        }
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
            
            com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
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
        
        redisTemplate.delete("order:ping:pending:" + orderId);
        redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
        logisticsDispatchService.releaseDriverLock(driverId.toString());
    }

    public void updateOrderStatus(UUID driverId, UUID orderId, String status) {
        log.info("Driver {} updating order {} to {}", driverId, orderId, status);
        
        String currentAssignee = redisTemplate.opsForValue().get("order:driver:lock:" + orderId);
        if (currentAssignee == null || !driverId.toString().equals(currentAssignee)) {
            log.info("Idempotent/Invalid update: Order {} is not assigned to driver {}", orderId, driverId);
            return;
        }

        transactionTemplate.execute(txStatus -> {
            String eventType;
            if ("DELIVERED".equals(status)) {
                eventType = EventType.ORDER_DELIVERED;
            } else if ("DELIVERY_FAILED".equals(status)) {
                eventType = EventType.DELIVERY_FAILED;
            } else {
                eventType = EventType.ORDER_STATUS_UPDATED;
            }
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
            
            com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
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
                com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
                state.completeDelivery(executive);
                executive.setUpdatedAt(java.time.LocalDateTime.now());
                repository.save(executive);
            }
            return null;
        });
        
        if ("DELIVERED".equals(status) || "DELIVERY_FAILED".equals(status)) {
            // Release the Redis driver lock so they can receive new pings
            logisticsDispatchService.releaseDriverLock(driverId.toString());
            // Release the order lock since the order has reached a terminal state
            redisTemplate.delete("order:driver:lock:" + orderId);
            redisTemplate.delete("order:ping:pending:" + orderId);
            redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
            redisTemplate.opsForValue().set("order:dispatch:lock:" + orderId, "accepted", java.time.Duration.ofHours(24));
            
            try {
                String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
                redisTemplate.opsForSet().add(key, driverId.toString());
            } catch (Exception e) {
                log.error("Failed to add driver {} back to Redis pool", driverId, e);
            }
        }
    }

    public void timeoutDriverPing(UUID driverId, UUID orderId) {
        log.info("Driver {} ping timed out for order {}", driverId, orderId);
        
        redisTemplate.delete("order:ping:pending:" + orderId);
        redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());

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
            
            com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
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
    
    public void forceAssignOrder(UUID orderId, UUID driverId) {
        log.info("Admin forcing assignment of order {} to driver {}", orderId, driverId);
        
        // Remove from pending ping if any
        redisTemplate.delete("order:ping:pending:" + orderId);
        redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());

        // Force set the order driver lock
        redisTemplate.opsForValue().set("order:driver:lock:" + orderId, driverId.toString(), java.time.Duration.ofMinutes(60));

        transactionTemplate.executeWithoutResult(status -> {
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
            
            com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(com.fooddelivery.common.constants.AppConstants.AGGREGATE_ORDER)
                    .aggregateId(orderId.toString())
                    .eventType(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED)
                    .payload(payload)
                    .createdAt(java.time.LocalDateTime.now())
                    .status(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_UNPROCESSED)
                    .build();
            outboxEventRepository.save(outboxEvent);
            
            DeliveryExecutive executive = repository.findLockedById(driverId).orElseThrow(() -> new IllegalArgumentException("Driver not found"));
            com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
            
            // Just force it to ONLINE if it's OFFLINE to allow assignment?
            // Actually, we expect them to be ONLINE, so state.acceptOrder should work.
            if (executive.getStatus() != com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE) {
                // To be safe, if they somehow went offline or are ON_DELIVERY already, force them to ONLINE first?
                // Let's just assume acceptOrder works for ONLINE drivers.
                if (executive.getStatus() == com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.OFFLINE) {
                     executive.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE);
                }
            }
            // re-fetch state in case we updated status
            state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
            state.acceptOrder(executive);
            repository.save(executive);
        });
        
        try {
            String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
            redisTemplate.opsForSet().remove(key, driverId.toString());
        } catch (Exception e) {
            log.error("Failed to remove driver {} from Redis pool", driverId, e);
        }
    }
}
