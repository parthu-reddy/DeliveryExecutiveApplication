package com.fooddelivery.delivery.service.state.order;

import com.fooddelivery.common.enums.OrderStatus;
import java.util.UUID;

public interface DeliveryOrderStateStrategy {
    void handleStatusUpdate(UUID driverId, UUID orderId, OrderStatus status, String pickupOtp, String deliveryOtp);
    OrderStatus getSupportedStatus();
}
