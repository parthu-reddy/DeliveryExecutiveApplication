package com.fooddelivery.delivery.enums;

public enum DeliveryExecutiveStatus {
    OFFLINE(10),
    ONLINE(20),
    ON_DELIVERY(30);

    private final int code;

    DeliveryExecutiveStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
