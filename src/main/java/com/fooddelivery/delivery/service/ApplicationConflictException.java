package com.fooddelivery.delivery.service;
import java.util.UUID;
public class ApplicationConflictException extends RuntimeException {
    private final UUID executiveId;
    public ApplicationConflictException(UUID id) { super("This application changed. Refresh it before deciding again."); executiveId=id; }
    public UUID executiveId() { return executiveId; }
}
