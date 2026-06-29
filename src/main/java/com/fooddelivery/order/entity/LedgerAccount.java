package com.fooddelivery.order.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ledger_accounts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerAccount {
    @Id
    private UUID id;

    private String ownerType;
    private UUID ownerId;
    
    private BigDecimal balance;

    @Version
    private Integer lockVersion;
}
