package com.fooddelivery.delivery.service.state;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.exception.IllegalStateTransitionException;

public interface DeliveryExecutiveState {
    default void goOnline(DeliveryExecutive executive) {
        throw new IllegalStateTransitionException("Cannot go online from state: " + executive.getStatus());
    }

    default void goOffline(DeliveryExecutive executive) {
        throw new IllegalStateTransitionException("Cannot go offline from state: " + executive.getStatus());
    }

    default void acceptOrder(DeliveryExecutive executive) {
        throw new IllegalStateTransitionException("Cannot accept order from state: " + executive.getStatus());
    }

    default void completeDelivery(DeliveryExecutive executive) {
        throw new IllegalStateTransitionException("Cannot complete delivery from state: " + executive.getStatus());
    }
}
