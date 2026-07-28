package com.fooddelivery.delivery.enums;

public enum AssignmentResult {
    ALREADY_ACCEPTED(10),
    INVALID(20),
    SUCCESS_EMPTY(30),
    LAST_REJECT(40),
    EMPTY(50),
    SUCCESS(60);

    private final int code;

    AssignmentResult(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
