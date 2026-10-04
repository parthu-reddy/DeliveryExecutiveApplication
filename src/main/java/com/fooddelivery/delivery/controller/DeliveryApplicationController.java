package com.fooddelivery.delivery.controller;
import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.delivery.dto.*;
import com.fooddelivery.delivery.service.DeliveryApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/delivery-onboarding/application") @RequiredArgsConstructor @PreAuthorize("isAuthenticated()")
public class DeliveryApplicationController {
    private final DeliveryApplicationService applications;
    @GetMapping public ApiResponse<DeliveryApplicationDto> mine() { return ApiResponse.success(applications.mine(),"Application retrieved"); }
    @PutMapping public ApiResponse<DeliveryApplicationDto> put(@Valid @RequestBody DeliveryApplicationDraftRequest request) { return ApiResponse.success(applications.put(request),"Draft saved"); }
    @PostMapping("/submit") public ApiResponse<DeliveryApplicationDto> submit(@Valid @RequestBody DeliveryApplicationDecisionRequest request) { return ApiResponse.success(applications.submit(request.version()),"Application submitted"); }
}
