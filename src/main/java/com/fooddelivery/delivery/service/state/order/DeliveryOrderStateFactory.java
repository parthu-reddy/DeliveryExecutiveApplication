package com.fooddelivery.delivery.service.state.order;

import com.fooddelivery.common.enums.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeliveryOrderStateFactory {

    private final Map<OrderStatus, DeliveryOrderStateStrategy> strategies = new HashMap<>();
    private final DefaultDeliveryOrderStateStrategy defaultStrategy;

    @Autowired
    public DeliveryOrderStateFactory(List<DeliveryOrderStateStrategy> strategyList, DefaultDeliveryOrderStateStrategy defaultStrategy) {
        for (DeliveryOrderStateStrategy strategy : strategyList) {
            if (strategy.getSupportedStatus() != null) {
                strategies.put(strategy.getSupportedStatus(), strategy);
            }
        }
        this.defaultStrategy = defaultStrategy;
    }

    public DeliveryOrderStateStrategy getStrategy(OrderStatus status) {
        return strategies.getOrDefault(status, defaultStrategy);
    }
}
