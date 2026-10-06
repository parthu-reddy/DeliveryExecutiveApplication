package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.governmentid.DeliveryApplicationContextDto;
import com.fooddelivery.delivery.service.DeliveryApplicationService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/delivery-applications")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SERVICE')")
public class InternalDeliveryApplicationController {
    private final DeliveryApplicationService applications;

    @GetMapping("/{executiveId}/context")
    public DeliveryApplicationContextDto context(@PathVariable UUID executiveId) {
        return applications.documentContext(executiveId);
    }
}
