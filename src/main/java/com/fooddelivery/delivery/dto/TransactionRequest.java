package com.fooddelivery.delivery.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TransactionRequest {
    private BigDecimal amount;
    private String referenceId;
    private String description;
}
