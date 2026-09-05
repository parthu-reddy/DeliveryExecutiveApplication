package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ledger.BeneficiaryResponse;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/drivers")
@RequiredArgsConstructor
public class InternalDriverBeneficiaryController {

    private final IDeliveryExecutiveRepository deliveryExecutiveRepository;

    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    @GetMapping("/{driverId}/beneficiary")
    public ResponseEntity<BeneficiaryResponse> getBeneficiary(@PathVariable UUID driverId) {
        return deliveryExecutiveRepository.findById(driverId)
                .map(driver -> {
                    BeneficiaryResponse response = BeneficiaryResponse.builder()
                            .beneficiaryName(driver.getFullName())
                            .accountNumberMasked("XXXX-XXXX-" + driverId.toString().substring(0, 4))
                            .ifsc("BANK001")
                            .verified(driver.getVerificationStatus() == com.fooddelivery.common.enums.VerificationStatus.APPROVED)
                            .source("DRIVER")
                            .build();
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
