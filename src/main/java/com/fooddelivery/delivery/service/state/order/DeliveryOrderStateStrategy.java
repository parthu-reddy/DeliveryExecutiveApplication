package com.fooddelivery.delivery.service.state.order;

import com.fooddelivery.common.enums.DeliveryStatus;
import java.util.UUID;

public interface DeliveryOrderStateStrategy {
    void handleStatusUpdate(UUID driverId, UUID orderId, DeliveryStatus status, String pickupOtp, String deliveryOtp, Boolean goOfflineAfter, java.math.BigDecimal cashCollectedAmount);
    DeliveryStatus getSupportedStatus();
}
