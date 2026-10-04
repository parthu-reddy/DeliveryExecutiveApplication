package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.delivery.service.DailySelfieRequiredException;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice @Order(0)
public class DailySelfieRequiredHandler {
    @ExceptionHandler(DailySelfieRequiredException.class)
    public ResponseEntity<ApiResponse<Void>> due(DailySelfieRequiredException refusal) {
        return ResponseEntity.badRequest().body(ApiResponse.error(refusal.getMessage(), "DAILY_SELFIE_REQUIRED"));
    }
}
