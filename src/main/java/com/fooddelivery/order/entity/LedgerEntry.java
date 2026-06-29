package com.fooddelivery.order.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {
    @Id
    private UUID id;

    private UUID transactionId;
    private UUID accountId;
    private String direction; // CREDIT, DEBIT
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
