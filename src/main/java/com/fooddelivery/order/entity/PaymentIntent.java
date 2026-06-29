package com.fooddelivery.order.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_intents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentIntent {
    @Id
    private UUID id;

    @Column(name = "internal_order_id", nullable = false)
    private UUID internalOrderId;

    @Column(name = "gateway_order_id")
    private String gatewayOrderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(length = 50)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
