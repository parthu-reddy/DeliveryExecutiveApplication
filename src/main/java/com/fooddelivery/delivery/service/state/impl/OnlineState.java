package com.fooddelivery.delivery.service.state.impl;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveState;

public class OnlineState implements DeliveryExecutiveState {
    @Override
    public void goOffline(DeliveryExecutive executive) {
        executive.setStatus(DeliveryExecutiveStatus.OFFLINE);
    }

    @Override
    public void acceptOrder(DeliveryExecutive executive) {
        executive.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);
    }
}
