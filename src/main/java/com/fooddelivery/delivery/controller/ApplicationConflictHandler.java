package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestControllerAdvice
@RequiredArgsConstructor
public class ApplicationConflictHandler {
    private final DeliveryApplicationService applications;

    @ExceptionHandler(ApplicationConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ApplicationConflictException changed) {
        var body = new HashMap<>(applications.currentForConflict(changed.executiveId()));
        body.put("message", changed.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
