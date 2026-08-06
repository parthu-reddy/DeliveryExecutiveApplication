package com.fooddelivery.delivery.client;

import com.fooddelivery.delivery.dto.WalletDto;
import com.fooddelivery.delivery.dto.TransactionRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.UUID;

@FeignClient(name = "wallet-service", path = "/api/v1/wallets")
public interface WalletClient {

    @GetMapping("/{entityType}/{entityId}")
    WalletDto getWallet(@PathVariable("entityType") String entityType, @PathVariable("entityId") UUID entityId);

    @PostMapping("/{entityType}/{entityId}/credit")
    WalletDto credit(@PathVariable("entityType") String entityType, @PathVariable("entityId") UUID entityId, @RequestBody TransactionRequest request);
    
    @PostMapping("/{entityType}/{entityId}/debit")
    WalletDto debit(@PathVariable("entityType") String entityType, @PathVariable("entityId") UUID entityId, @RequestBody TransactionRequest request);
}
