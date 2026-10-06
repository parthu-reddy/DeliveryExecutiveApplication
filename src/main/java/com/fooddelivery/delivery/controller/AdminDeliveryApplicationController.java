package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.enums.ApplicationStatus;
import com.fooddelivery.delivery.dto.*;
import com.fooddelivery.delivery.service.DeliveryApplicationService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Slice;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/admin/delivery-applications")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDeliveryApplicationController {
    private final DeliveryApplicationService applications;

    @GetMapping
    public ApiResponse<Slice<DeliveryApplicationQueueDto>> queue(
            @RequestParam(defaultValue = "IN_REVIEW") ApplicationStatus status,
            @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.success(applications.queue(status, page), "Applications retrieved");
    }

    @GetMapping("/{executiveId}")
    public ApiResponse<DeliveryApplicationDto> detail(@PathVariable UUID executiveId) {
        return ApiResponse.success(applications.adminDetail(executiveId), "Application retrieved");
    }

    @GetMapping("/{executiveId}/history")
    public ApiResponse<Slice<com.fooddelivery.common.audit.AuditEvent>> history(
            @PathVariable UUID executiveId, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.success(
                applications.history(executiveId, page), "Application history retrieved");
    }

    @PostMapping("/{executiveId}/approve")
    public ApiResponse<DeliveryApplicationDto> approve(
            @PathVariable UUID executiveId,
            @Valid @RequestBody DeliveryApplicationDecisionRequest request) {
        return ApiResponse.success(
                applications.approve(executiveId, request), "Application approved");
    }

    @PostMapping("/{executiveId}/reject")
    public ApiResponse<DeliveryApplicationDto> reject(
            @PathVariable UUID executiveId,
            @Valid @RequestBody DeliveryApplicationDecisionRequest request) {
        return ApiResponse.success(
                applications.reject(executiveId, request), "Application rejected");
    }

    @PostMapping("/{executiveId}/suspend")
    public ApiResponse<DeliveryApplicationDto> suspend(
            @PathVariable UUID executiveId,
            @Valid @RequestBody DeliveryApplicationDecisionRequest request) {
        return ApiResponse.success(
                applications.suspend(executiveId, request), "Application suspended");
    }

    @PostMapping("/{executiveId}/reinstate")
    public ApiResponse<DeliveryApplicationDto> reinstate(
            @PathVariable UUID executiveId,
            @Valid @RequestBody DeliveryApplicationDecisionRequest request) {
        return ApiResponse.success(
                applications.reinstate(executiveId, request), "Application reinstated");
    }
}
