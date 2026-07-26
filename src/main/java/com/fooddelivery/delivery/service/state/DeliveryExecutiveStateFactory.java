package com.fooddelivery.delivery.service.state;

import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.service.state.impl.OfflineState;
import com.fooddelivery.delivery.service.state.impl.OnDeliveryState;
import com.fooddelivery.delivery.service.state.impl.OnlineState;

import java.util.EnumMap;
import java.util.Map;

public class DeliveryExecutiveStateFactory {

    private static final EnumMap<DeliveryExecutiveStatus, DeliveryExecutiveState> STATES;

    static {
        STATES = new EnumMap<>(DeliveryExecutiveStatus.class);
        STATES.put(DeliveryExecutiveStatus.OFFLINE, new OfflineState());
        STATES.put(DeliveryExecutiveStatus.ONLINE, new OnlineState());
        STATES.put(DeliveryExecutiveStatus.ON_DELIVERY, new OnDeliveryState());
    }

    private static final DeliveryExecutiveState DEFAULT_STATE = STATES.get(DeliveryExecutiveStatus.OFFLINE);

    public static DeliveryExecutiveState getState(DeliveryExecutiveStatus status) {
        if (status == null) {
            return DEFAULT_STATE;
        }
        return STATES.getOrDefault(status, DEFAULT_STATE);
    }
}
