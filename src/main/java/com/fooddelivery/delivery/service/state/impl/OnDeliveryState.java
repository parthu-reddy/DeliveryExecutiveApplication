package com.fooddelivery.delivery.service.state.impl;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveState;

public class OnDeliveryState implements DeliveryExecutiveState {
    @Override
    public void completeDelivery(DeliveryExecutive executive) {
        executive.setStatus(DeliveryExecutiveStatus.ONLINE);
    }
}
