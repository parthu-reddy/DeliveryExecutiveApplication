package com.fooddelivery.delivery.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class WalletDto {
    private UUID id;
    private UUID entityId;
    private String entityType;
    private BigDecimal balance;
    private String currency;
    private String status;
}
