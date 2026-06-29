package com.fooddelivery.common.exception;

public class OrderProcessingException extends RuntimeException {
    public OrderProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
