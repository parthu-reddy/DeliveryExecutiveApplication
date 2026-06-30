package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {

    private final IDeliveryExecutiveRepository repository;

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
        
        return repository.save(executive);
    }

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LogisticsDispatchService logisticsDispatchService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private static final String TOPIC = "order-events";

    @Transactional
    public void acceptOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} attempting to accept order {}", driverId, orderId);
        
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent("order:driver:lock:" + orderId, driverId.toString(), java.time.Duration.ofMinutes(60));
        if (Boolean.FALSE.equals(acquired)) {
            log.warn("Order {} was already accepted by another driver. Driver {} ping rejected.", orderId, driverId);
            throw new IllegalStateException("Order is no longer available.");
        }
        
        // Emitting event to CustomerApplication
        String payload = "{\"eventType\":\"DRIVER_ASSIGNED\", \"orderId\":\"" + orderId + "\", \"driverId\":\"" + driverId + "\"}";
        kafkaTemplate.send(TOPIC, orderId.toString(), payload);
        
        DeliveryExecutive executive = repository.findById(driverId).orElseThrow();
        executive.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);
        repository.save(executive);
        
        log.info("Driver {} accepted order {}. Emitted DRIVER_ASSIGNED event.", driverId, orderId);
    }

    @Transactional
    public void rejectOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} rejected order ping {}", driverId, orderId);
        
        logisticsDispatchService.releaseDriverLock(driverId.toString());
        
        String payload = "{\"eventType\":\"ORDER_DRIVER_REJECTED\", \"orderId\":\"" + orderId + "\", \"driverId\":\"" + driverId + "\"}";
        kafkaTemplate.send(TOPIC, orderId.toString(), payload);
    }

    @Transactional
    public void updateOrderStatus(UUID driverId, UUID orderId, String status) {
        log.info("Driver {} updating order {} to {}", driverId, orderId, status);
        
        String eventType = "DELIVERED".equals(status) ? "ORDER_DELIVERED" : "ORDER_STATUS_UPDATED";
        String payload = "{\"eventType\":\"" + eventType + "\", \"orderId\":\"" + orderId + "\", \"status\":\"" + status + "\"}";
        kafkaTemplate.send(TOPIC, orderId.toString(), payload);
        
        if ("DELIVERED".equals(status) || "DELIVERY_FAILED".equals(status)) {
            DeliveryExecutive executive = repository.findById(driverId).orElseThrow();
            executive.setStatus(DeliveryExecutiveStatus.ONLINE);
            executive.setUpdatedAt(java.time.LocalDateTime.now());
            repository.save(executive);
            
            // Release the Redis driver lock so they can receive new pings
            logisticsDispatchService.releaseDriverLock(driverId.toString());
        }
    }

    @Transactional
    public void timeoutDriverPing(UUID driverId, UUID orderId) {
        log.info("Driver {} ping timed out for order {}", driverId, orderId);
        
        logisticsDispatchService.releaseDriverLock(driverId.toString());
        
        String payload = "{\"eventType\":\"ORDER_DRIVER_REJECTED\", \"orderId\":\"" + orderId + "\", \"driverId\":\"" + driverId + "\"}";
        kafkaTemplate.send(TOPIC, orderId.toString(), payload);
    }
}
