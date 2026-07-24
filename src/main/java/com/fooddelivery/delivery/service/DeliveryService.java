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
import com.fooddelivery.common.enums.OrderStatus;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {

    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private final IDeliveryExecutiveRepository repository;
    private final ObjectMapper objectMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final com.fooddelivery.delivery.service.state.order.DeliveryOrderStateFactory deliveryOrderStateFactory;

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
            
            if (isOnline && (executive.getStatus() == DeliveryExecutiveStatus.ONLINE || executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY)) return executive;
            if (!isOnline && executive.getStatus() == DeliveryExecutiveStatus.OFFLINE) return executive;
            if (!isOnline && executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY) {
                throw new IllegalArgumentException("Cannot go offline while on delivery. Please complete the delivery first.");
            }

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
                redisTemplate.opsForZSet().add("driver_last_ping", driverId.toString(), System.currentTimeMillis());
            } else {
                redisTemplate.opsForSet().remove(key, driverId.toString());
                redisTemplate.opsForZSet().remove("driver_last_ping", driverId.toString());
            }
        } catch (Exception e) {
            log.error("Failed to sync driver status with Redis for driver {}", driverId, e);
            // Non-fatal, but could cause temporary dispatch mismatch
        }
        
        log.info("Driver {} is now {}", driverId, updated.getStatus());
        return updated;
    }

    public java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> getAvailableDriversWithLocation() {
        java.util.List<DeliveryExecutive> availableDrivers = repository.findByStatus(DeliveryExecutiveStatus.ONLINE);
        System.out.println("getAvailableDriversWithLocation: found " + availableDrivers.size() + " drivers ONLINE");
        java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> result = new java.util.ArrayList<>();
        String DRIVER_LOCATION_KEY = "drivers:geo:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
        
        for (DeliveryExecutive driver : availableDrivers) {
            com.fooddelivery.delivery.dto.DriverLocationDTO dto = new com.fooddelivery.delivery.dto.DriverLocationDTO(
                driver.getId(), driver.getFullName(), driver.getPhoneNumber(), null, null, driver.getStatus().toString()
            );
            try {
                java.util.List<org.springframework.data.geo.Point> positions = redisTemplate.opsForGeo().position(DRIVER_LOCATION_KEY, driver.getId().toString());
                if (positions != null && !positions.isEmpty() && positions.get(0) != null) {
                    dto.setLng(positions.get(0).getX());
                    dto.setLat(positions.get(0).getY());
                    result.add(dto);
                } else {
                    log.debug("Skipping driver {} as they have no valid location in Redis", driver.getId());
                }
            } catch (Exception e) {
                log.warn("Could not fetch location for driver {}", driver.getId());
            }
        }
        return result;
    }

    public java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> getAllDriversWithLocation() {
        java.util.List<DeliveryExecutive> allDrivers = repository.findAll();
        System.out.println("getAllDriversWithLocation: found " + allDrivers.size() + " drivers");
        java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> result = new java.util.ArrayList<>();
        String DRIVER_LOCATION_KEY = "drivers:geo:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
        
        for (DeliveryExecutive driver : allDrivers) {
            com.fooddelivery.delivery.dto.DriverLocationDTO dto = new com.fooddelivery.delivery.dto.DriverLocationDTO(
                driver.getId(), driver.getFullName(), driver.getPhoneNumber(), null, null, driver.getStatus().toString()
            );
            

            try {
                java.util.List<org.springframework.data.geo.Point> positions = redisTemplate.opsForGeo().position(DRIVER_LOCATION_KEY, driver.getId().toString());
                if (positions != null && !positions.isEmpty() && positions.get(0) != null) {
                    dto.setLng(positions.get(0).getX());
                    dto.setLat(positions.get(0).getY());
                    result.add(dto);
                } else {
                    // Even if location is null, we might want to return them with 0.0 or exclude them.
                    // For admin map, we should exclude drivers without any known location, or provide fallback.
                    dto.setLat(0.0);
                    dto.setLng(0.0);
                    result.add(dto);
                }
            } catch (Exception e) {
                log.warn("Could not fetch location for driver {}", driver.getId());
            }
        }
        return result;
    }

    private final OutboxEventRepository outboxEventRepository;
    private final LogisticsDispatchService logisticsDispatchService;
    private static final String TOPIC = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS;

    public String getPendingPing(UUID driverId) {
        return redisTemplate.opsForValue().get("driver:pending_ping:" + driverId);
    }
    
    public Long getPingExpiration(UUID orderId) {
        Double score = redisTemplate.opsForZSet().score("order:ping:timeouts", orderId.toString());
        return score != null ? score.longValue() : null;
    }


    private static final String ACCEPT_SCRIPT = 
            "local pendingKey = KEYS[1]\n" +
            "local lockKey = KEYS[2]\n" +
            "local driverId = ARGV[1]\n" +
            "if redis.call('EXISTS', lockKey) == 1 then return {'ALREADY_ACCEPTED'} end\n" +
            "if redis.call('SISMEMBER', pendingKey, driverId) == 0 then return {'INVALID'} end\n" +
            "redis.call('SET', lockKey, driverId, 'EX', 3600)\n" +
            "local allPinged = redis.call('SMEMBERS', pendingKey)\n" +
            "redis.call('DEL', pendingKey)\n" +
            "if #allPinged == 0 then return {'SUCCESS_EMPTY'} end\n" +
            "return allPinged";

    public void acceptOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} attempting to accept order {}", driverId, orderId);
        
        RedisScript<List> script = new DefaultRedisScript<>(ACCEPT_SCRIPT, List.class);
        List<String> result = redisTemplate.execute(script, Arrays.asList("order:ping:pending:" + orderId, "order:driver:lock:" + orderId), driverId.toString());

        if (result == null) {
            throw new IllegalStateException("Order is no longer available.");
        }
        if (result.size() == 1 && "ALREADY_ACCEPTED".equals(result.get(0))) {
            log.warn("Order {} was already accepted by another driver. Driver {} ping rejected.", orderId, driverId);
            throw new IllegalStateException("Order is no longer available.");
        }
        if (result.size() == 1 && "INVALID".equals(result.get(0))) {
            log.warn("Ping for order {} and driver {} is invalid or expired.", orderId, driverId);
            throw new IllegalStateException("Ping expired or invalid.");
        }
        
        try {
            transactionTemplate.executeWithoutResult(status -> {
                String currentLock = redisTemplate.opsForValue().get("order:driver:lock:" + orderId);
                if (!driverId.toString().equals(currentLock)) {
                    throw new IllegalStateException("Order lock was lost to cancellation. Aborting assignment.");
                }

                DeliveryExecutive executive = repository.findLockedById(driverId).orElseThrow();

                com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.createObjectNode();
                payloadNode.put("eventType", com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED.name());
                payloadNode.put("orderId", orderId.toString());
                payloadNode.put("driverId", driverId.toString());
                payloadNode.put("driverName", executive.getFullName());
                payloadNode.put("driverPhone", executive.getPhoneNumber());
                String payload;
                try {
                    payload = objectMapper.writeValueAsString(payloadNode);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize DRIVER_ASSIGNED payload", e);
                }
                
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AggregateType.ORDER)
                        .aggregateId(orderId.toString())
                        .eventType(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED)
                        .payload(payload)
                        .createdAt(java.time.LocalDateTime.now())
                        .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                        .build();
                log.info("Triggering event: DRIVER_ASSIGNED for executive: {}", executive.getId());
                outboxEventRepository.save(outboxEvent);
                
                com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
                state.acceptOrder(executive);
                repository.save(executive);
            });
            log.info("Driver {} accepted order {}. Emitted DRIVER_ASSIGNED event.", driverId, orderId);
            
            if (result.size() > 0 && !"SUCCESS_EMPTY".equals(result.get(0))) {
                for (String pingedDriver : result) {
                    redisTemplate.delete("driver:pending_ping:" + pingedDriver);
                }
            }
            redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
            
            try {
                String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
                redisTemplate.opsForSet().remove(key, driverId.toString());
            } catch (Exception e) {
                log.error("Failed to remove driver {} from Redis pool", driverId, e);
            }
        } catch (Exception e) {
            log.error("Failed to commit DRIVER_ASSIGNED transaction. Releasing Redis lock for order {}", orderId, e);
            String currentLock = redisTemplate.opsForValue().get("order:driver:lock:" + orderId);
            if (driverId.toString().equals(currentLock)) {
                redisTemplate.delete("order:driver:lock:" + orderId);
            }
            if (result != null && !result.isEmpty() && !"SUCCESS_EMPTY".equals(result.get(0))) {
                redisTemplate.opsForSet().add("order:ping:pending:" + orderId, result.toArray(new String[0]));
            }
            throw e;
        }
    }

    private static final String REJECT_SCRIPT = 
            "local pendingKey = KEYS[1]\n" +
            "local lockKey = KEYS[2]\n" +
            "local driverId = ARGV[1]\n" +
            "if redis.call('EXISTS', lockKey) == 1 then\n" +
            "    redis.call('SREM', pendingKey, driverId)\n" +
            "    return 'ACCEPTED_ALREADY'\n" +
            "end\n" +
            "local removed = redis.call('SREM', pendingKey, driverId)\n" +
            "if removed == 0 then return 'NOT_FOUND' end\n" +
            "local remaining = redis.call('SCARD', pendingKey)\n" +
            "if remaining == 0 then return 'LAST_REJECT' end\n" +
            "return 'REJECTED'";

    public void rejectOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} rejected order ping {}", driverId, orderId);
        
        RedisScript<String> script = new DefaultRedisScript<>(REJECT_SCRIPT, String.class);
        String result = redisTemplate.execute(script, Arrays.asList("order:ping:pending:" + orderId, "order:driver:lock:" + orderId), driverId.toString());

        redisTemplate.delete("driver:pending_ping:" + driverId);
        redisTemplate.opsForSet().add("order:rejected_drivers:" + orderId, driverId.toString());
        redisTemplate.expire("order:rejected_drivers:" + orderId, java.time.Duration.ofHours(2));
        try {
            logisticsDispatchService.releaseDriverLock(driverId.toString());
        } catch (Exception e) {
            log.error("Failed to release driver lock for driver {} on reject, will be retried by availability poller", driverId, e);
        }

        if ("LAST_REJECT".equals(result)) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.createObjectNode();
                    payloadNode.put("eventType", com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED.name());
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
                            .aggregateType(com.fooddelivery.common.constants.AggregateType.ORDER)
                            .aggregateId(orderId.toString())
                            .eventType(com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED)
                            .payload(payload)
                            .createdAt(java.time.LocalDateTime.now())
                            .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                            .build();
                    log.info("Triggering event: ORDER_DRIVER_REJECTED for aggregate: {}", orderId);
                    outboxEventRepository.save(outboxEvent);
                });
                redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
            } catch (Exception e) {
                log.error("Failed to save ORDER_DRIVER_REJECTED event to outbox. Reverting Redis state for order {}", orderId, e);
                redisTemplate.opsForSet().add("order:ping:pending:" + orderId, driverId.toString());
                throw e;
            }
        }
    }


    public void abortOrder(UUID driverId, UUID orderId) {
        String currentLock = redisTemplate.opsForValue().get("order:driver:lock:" + orderId);
        if (currentLock == null || !currentLock.equals(driverId.toString())) {
            throw new IllegalArgumentException("Driver is not assigned to this order or order lock missing");
        }

        // 1. DB transaction: mark driver available and save outbox event atomically
        transactionTemplate.executeWithoutResult(status -> {
            DeliveryExecutive executive = repository.findLockedById(driverId)
                    .orElseThrow(() -> new IllegalArgumentException("Driver not found"));
            
            executive.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE);
            repository.save(executive);
            
            // Save outbox event in the same transaction
            com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.createObjectNode();
            payloadNode.put("eventType", com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED.name());
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
                    .aggregateType(com.fooddelivery.common.constants.AggregateType.ORDER)
                    .aggregateId(orderId.toString())
                    .eventType(com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED)
                    .payload(payload)
                    .createdAt(java.time.LocalDateTime.now())
                    .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                    .build();
            outboxEventRepository.save(outboxEvent);
        });

        // 2. Redis ops AFTER DB commit — if these fail, the outbox event still fires correctly
        redisTemplate.delete("order:driver:lock:" + orderId);
        redisTemplate.opsForSet().add("order:rejected_drivers:" + orderId, driverId.toString());
        redisTemplate.expire("order:rejected_drivers:" + orderId, java.time.Duration.ofHours(2));
        
        // Add back to pool
        try {
            String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
            redisTemplate.opsForSet().add(key, driverId.toString());
        } catch (Exception e) {
            log.error("Failed to add driver {} to Redis pool", driverId, e);
        }
        
        try {
            logisticsDispatchService.releaseDriverLock(driverId.toString());
        } catch (Exception e) {
            log.error("Failed to release driver lock for driver {} on abort, will be retried by availability poller", driverId, e);
        }

        // 3. Trigger dispatch loop again to find a new driver
        log.info("Driver {} aborted order {}. Re-triggering candidate search...", driverId, orderId);
    }

    public void updateOrderStatus(UUID driverId, UUID orderId, DeliveryStatus status, String pickupOtp, String deliveryOtp, Boolean goOfflineAfter) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                com.fooddelivery.delivery.service.state.order.DeliveryOrderStateStrategy strategy = deliveryOrderStateFactory.getStrategy(status);
                strategy.handleStatusUpdate(driverId, orderId, status, pickupOtp, deliveryOtp, goOfflineAfter);
                return;
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                if (i == maxRetries - 1) {
                    log.error("Optimistic locking failure while updating order status for driver {}, order {}", driverId, orderId, e);
                    throw new RuntimeException("Concurrent update conflict. Please try again.", e);
                }
                log.warn("Optimistic locking failure for driver {}. Retrying {}/{}", driverId, i + 1, maxRetries);
                try {
                    Thread.sleep(100 + (long)(Math.random() * 100)); // basic jitter
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static final String TIMEOUT_SCRIPT =
            "local pendingKey = KEYS[1]\n" +
            "local lockKey = KEYS[2]\n" +
            "if redis.call('EXISTS', lockKey) == 1 then\n" +
            "    redis.call('DEL', pendingKey)\n" +
            "    return {'ALREADY_ACCEPTED'}\n" +
            "end\n" +
            "local pendingDrivers = redis.call('SMEMBERS', pendingKey)\n" +
            "if #pendingDrivers == 0 then return {'EMPTY'} end\n" +
            "redis.call('DEL', pendingKey)\n" +
            "return pendingDrivers";

    public void timeoutOrderPing(UUID orderId) {
        log.info("Order ping timed out for order {}", orderId);
        String orderIdStr = orderId.toString();

        RedisScript<List> script = new DefaultRedisScript<>(TIMEOUT_SCRIPT, List.class);
        List<String> result = redisTemplate.execute(script, Arrays.asList("order:ping:pending:" + orderIdStr, "order:driver:lock:" + orderIdStr));

        if (result == null || result.isEmpty()) {
            redisTemplate.opsForZSet().remove("order:ping:timeouts", orderIdStr);
            return;
        }

        if ("ALREADY_ACCEPTED".equals(result.get(0)) || "EMPTY".equals(result.get(0))) {
            log.info("Order {} ping phase already finished (status: {}). Cleaning up orphan timeout.", orderId, result.get(0));
            redisTemplate.opsForZSet().remove("order:ping:timeouts", orderIdStr);
            return;
        }

        for (String driverIdStr : result) {
            redisTemplate.delete("driver:pending_ping:" + driverIdStr);
            redisTemplate.opsForSet().add("order:rejected_drivers:" + orderIdStr, driverIdStr);
            redisTemplate.expire("order:rejected_drivers:" + orderIdStr, java.time.Duration.ofHours(2));
            try {
                logisticsDispatchService.releaseDriverLock(driverIdStr);
            } catch (Exception e) {
                log.error("Failed to release driver lock for driver {} on timeout, will be retried by availability poller", driverIdStr, e);
            }
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.createObjectNode();
                payloadNode.put("eventType", com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED.name());
                payloadNode.put("orderId", orderIdStr);
                payloadNode.put("driverId", result.get(0)); // Include one driver ID for logging purposes
                String payload;
                try {
                    payload = objectMapper.writeValueAsString(payloadNode);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize ORDER_DRIVER_REJECTED payload", e);
                }
                
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AggregateType.ORDER)
                        .aggregateId(orderIdStr)
                        .eventType(com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED)
                        .payload(payload)
                        .createdAt(java.time.LocalDateTime.now())
                        .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                        .build();
                log.info("Triggering event: ORDER_DRIVER_REJECTED for aggregate: {}", orderIdStr);
                outboxEventRepository.save(outboxEvent);
            });
            redisTemplate.opsForZSet().remove("order:ping:timeouts", orderIdStr);
        } catch (Exception e) {
            log.error("Failed to save ORDER_DRIVER_REJECTED event to outbox. Reverting Redis state for order {}", orderIdStr, e);
            if (result != null && !result.isEmpty()) {
                redisTemplate.opsForSet().add("order:ping:pending:" + orderIdStr, result.toArray(new String[0]));
            }
            throw e;
        }
    }
    
    public void forceAssignOrder(UUID orderId, UUID driverId) {
        log.info("Admin forcing assignment of order {} to driver {}", orderId, driverId);
        
        // Clean up ALL pending pings for drivers that were being pinged
        java.util.Set<String> pendingDrivers = redisTemplate.opsForSet().members("order:ping:pending:" + orderId);
        redisTemplate.delete("order:ping:pending:" + orderId);
        if (pendingDrivers != null) {
            for (String pendingDriverId : pendingDrivers) {
                redisTemplate.delete("driver:pending_ping:" + pendingDriverId);
            }
        }
        redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
        redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId.toString());

        // Force set the order driver lock
        redisTemplate.opsForValue().set("order:driver:lock:" + orderId, driverId.toString(), java.time.Duration.ofMinutes(60));

        try {
            transactionTemplate.executeWithoutResult(status -> {
                DeliveryExecutive executive = repository.findLockedById(driverId).orElseThrow(() -> new IllegalArgumentException("Driver not found"));
                com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.createObjectNode();
                payloadNode.put("eventType", com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED.name());
                payloadNode.put("orderId", orderId.toString());
                payloadNode.put("driverId", driverId.toString());
                payloadNode.put("driverName", executive.getFullName());
                payloadNode.put("driverPhone", executive.getPhoneNumber());
                String payload;
                try {
                    payload = objectMapper.writeValueAsString(payloadNode);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize DRIVER_ASSIGNED payload", e);
                }
                
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AggregateType.ORDER)
                        .aggregateId(orderId.toString())
                        .eventType(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED)
                        .payload(payload)
                        .createdAt(java.time.LocalDateTime.now())
                        .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                        .build();
                log.info("Triggering event: DELIVERY_EXECUTIVE_STATUS_CHANGED for executive: {}", driverId);
                outboxEventRepository.save(outboxEvent);
                
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
        } catch (Exception e) {
            log.error("Failed to commit DRIVER_ASSIGNED transaction in forceAssignOrder. Releasing Redis lock for order {}", orderId, e);
            redisTemplate.delete("order:driver:lock:" + orderId);
            throw e;
        }
        
        try {
            String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
            redisTemplate.opsForSet().remove(key, driverId.toString());
        } catch (Exception e) {
            log.error("Failed to remove driver {} from Redis pool", driverId, e);
        }
    }
}
