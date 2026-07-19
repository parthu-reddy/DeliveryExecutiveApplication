package com.fooddelivery.delivery.service.state.impl;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveState;

public class OfflineState implements DeliveryExecutiveState {
    @Override
    public void goOnline(DeliveryExecutive executive) {
        executive.setStatus(DeliveryExecutiveStatus.ONLINE);
    }

    @Override
    public void completeDelivery(DeliveryExecutive executive) {
        // If a driver was marked OFFLINE due to a connection drop during a delivery,
        // we should still allow them to complete the delivery and transition back to ONLINE.
        executive.setStatus(DeliveryExecutiveStatus.ONLINE);
    }
}
