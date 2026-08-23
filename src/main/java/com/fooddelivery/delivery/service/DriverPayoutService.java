package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.client.WalletServiceClient;
import com.fooddelivery.common.dto.wallet.WalletDto;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@lombok.extern.slf4j.Slf4j
public class DriverPayoutService {
private final WalletServiceClient walletClient;

    public DriverPayoutService(WalletServiceClient walletClient) {
        this.walletClient = walletClient;
    }

    public WalletDto getDriverBalance(UUID driverId) {
        try {
            return walletClient.getWallet("DRIVER", driverId);
        } catch (Exception e) {
            log.error("Failed to fetch balance for driver {}", driverId, e);
            throw new RuntimeException("Failed to fetch wallet balance", e);
        }
    }
}
