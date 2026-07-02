package com.fooddelivery.delivery.service.state;

import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.service.state.impl.OfflineState;
import com.fooddelivery.delivery.service.state.impl.OnDeliveryState;
import com.fooddelivery.delivery.service.state.impl.OnlineState;

public class DeliveryExecutiveStateFactory {

    private static final DeliveryExecutiveState OFFLINE = new OfflineState();
    private static final DeliveryExecutiveState ONLINE = new OnlineState();
    private static final DeliveryExecutiveState ON_DELIVERY = new OnDeliveryState();

    public static DeliveryExecutiveState getState(DeliveryExecutiveStatus status) {
        if (status == null) {
            return OFFLINE;
        }

        switch (status) {
            case OFFLINE:
                return OFFLINE;
            case ONLINE:
                return ONLINE;
            case ON_DELIVERY:
                return ON_DELIVERY;
            default:
                return OFFLINE;
        }
    }
}
