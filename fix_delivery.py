import re

with open("src/main/java/com/fooddelivery/delivery/service/OrderEventConsumer.java", "r") as f:
    content = f.read()

import_str = """import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;"""

content = content.replace("import lombok.extern.slf4j.Slf4j;\nimport org.springframework.kafka.annotation.KafkaListener;\nimport org.springframework.stereotype.Service;\n\nimport java.util.UUID;", import_str)

repo_str = """    private final LogisticsDispatchService logisticsDispatchService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final IDeliveryExecutiveRepository executiveRepository;"""

content = content.replace("    private final LogisticsDispatchService logisticsDispatchService;\n    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;", repo_str)


logic_old = """            } else if ("DRIVER_ASSIGNED".equals(eventType) || "DISPATCH_FAILED".equals(eventType) || "ORDER_CANCELLED".equals(eventType) || "DELIVERY_FAILED".equals(eventType) || "ORDER_CANCELLED_BY_RESTAURANT".equals(eventType) || "ORDER_REJECTED".equals(eventType) || "ORDER_DELAY_REJECTED".equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                String driverId = root.path("driverId").asText(null);
                log.info("Delivery Application received {} for order {}. Cleaning up pending dispatches.", eventType, orderId);
                
                // Cleanup the Redis dispatch payload on any terminal/successful state
                redisTemplate.delete("order:dispatchPayload:" + orderId);
                redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId.toString());

                if (("ORDER_CANCELLED".equals(eventType) || "DELIVERY_FAILED".equals(eventType) || "ORDER_CANCELLED_BY_RESTAURANT".equals(eventType) || "ORDER_REJECTED".equals(eventType) || "ORDER_DELAY_REJECTED".equals(eventType)) && driverId != null && !driverId.isEmpty()) {
                    logisticsDispatchService.releaseDriverLock(driverId);
                }
            }"""


logic_new = """            } else if ("DRIVER_ASSIGNED".equals(eventType) || "DISPATCH_FAILED".equals(eventType) || "ORDER_CANCELLED".equals(eventType) || "DELIVERY_FAILED".equals(eventType) || "ORDER_CANCELLED_BY_RESTAURANT".equals(eventType) || "ORDER_REJECTED".equals(eventType) || "ORDER_DELAY_REJECTED".equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                String driverId = root.path("driverId").asText(null);
                log.info("Delivery Application received {} for order {}. Cleaning up pending dispatches.", eventType, orderId);
                
                // Cleanup the Redis dispatch payload on any terminal/successful state
                redisTemplate.delete("order:dispatchPayload:" + orderId);
                redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId.toString());

                if ("ORDER_CANCELLED".equals(eventType) || "DELIVERY_FAILED".equals(eventType) || "ORDER_CANCELLED_BY_RESTAURANT".equals(eventType) || "ORDER_REJECTED".equals(eventType) || "ORDER_DELAY_REJECTED".equals(eventType)) {
                    if (driverId == null || driverId.isEmpty()) {
                        driverId = redisTemplate.opsForValue().get("order:driver:lock:" + orderId);
                    }
                    if (driverId != null && !driverId.isEmpty()) {
                        logisticsDispatchService.releaseDriverLock(driverId);
                        
                        // Reset driver status in DB
                        try {
                            DeliveryExecutive executive = executiveRepository.findById(UUID.fromString(driverId)).orElse(null);
                            if (executive != null && executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY) {
                                executive.setStatus(DeliveryExecutiveStatus.ONLINE);
                                executive.setUpdatedAt(java.time.LocalDateTime.now());
                                executiveRepository.save(executive);
                                log.info("Reset driver {} to ONLINE after order {} was cancelled.", driverId, orderId);
                            }
                        } catch (Exception ex) {
                            log.error("Failed to reset driver status in DB", ex);
                        }
                    }
                }
            }"""

content = content.replace(logic_old, logic_new)

with open("src/main/java/com/fooddelivery/delivery/service/OrderEventConsumer.java", "w") as f:
    f.write(content)
