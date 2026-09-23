package com.fooddelivery.delivery.service.task;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisLockReaperAssignmentSafetyTest {

    @Test
    void offlineRiderKeepsActiveOrderLockBecauseDisconnectDoesNotEndDelivery() {
        DeliveryExecutive rider = riderWithStatus(DeliveryExecutiveStatus.OFFLINE);

        assertThat(RedisLockReaperTask.isSafeToReap(rider)).isFalse();
    }

    @Test
    void onDeliveryRiderAlwaysKeepsActiveOrderLock() {
        DeliveryExecutive rider = riderWithStatus(DeliveryExecutiveStatus.ON_DELIVERY);

        assertThat(RedisLockReaperTask.isSafeToReap(rider)).isFalse();
    }

    @Test
    void onlineRiderAllowsCleanupOfLockLeftBehindByCompletedDelivery() {
        DeliveryExecutive rider = riderWithStatus(DeliveryExecutiveStatus.ONLINE);

        assertThat(RedisLockReaperTask.isSafeToReap(rider)).isTrue();
    }

    private DeliveryExecutive riderWithStatus(DeliveryExecutiveStatus status) {
        DeliveryExecutive rider = new DeliveryExecutive();
        rider.setStatus(status);
        return rider;
    }
}
