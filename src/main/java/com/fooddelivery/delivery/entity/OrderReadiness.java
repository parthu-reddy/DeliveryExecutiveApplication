package com.fooddelivery.delivery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * When the restaurant marked an order ready (OrderReadyEvent.occurredAt), whether or not a rider holds it yet.
 *
 * <p>Dispatch is often held back until the food is nearly ready, so ORDER_READY regularly arrives before an assignment row
 * exists. Kept here so recordAssignment can copy it onto the assignment, where OverdueRiderSweeper reads it (D21). The first
 * time wins: a replayed ORDER_READY carries the same time.
 */
@Entity
@Table(name = "order_readiness")
@lombok.Getter
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class OrderReadiness {
    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "ready_at", nullable = false)
    private Instant readyAt;
}
