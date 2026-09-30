package com.fooddelivery.delivery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * The record of which driver holds an order, and the OTPs that prove each handover.
 *
 * <p>This is the authority for both. Redis still carries the dispatch working set — candidate
 * pings, rejection counters, the delayed queue — but it is no longer what an authorization or an
 * OTP decision is read from: those keys carry a 24-hour TTL, and an evicted key used to mean
 * "anyone may update this order" and "this order can never be delivered" respectively.
 */
@Entity
@Table(name = "order_assignments")
@lombok.Getter
@lombok.Setter
@lombok.Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class OrderAssignment {

    public enum State {
        /** The driver holds the order. */
        ASSIGNED,
        /** The driver gave it up, or the delivery ended. Kept as a record, no longer authorising. */
        RELEASED
    }

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "pickup_otp")
    private String pickupOtp;

    @Column(name = "delivery_otp")
    private String deliveryOtp;

    /** The completed prepaid method attached to this delivery. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private com.fooddelivery.common.enums.PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private State state;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    /**
     * Reassignment and rider status updates can arrive at the same time. The version makes a
     * stale update retry instead of silently restoring an old driver to an already reassigned row.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /** The furthest step the rider has confirmed. Only moves forward. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status")
    private com.fooddelivery.common.enums.DeliveryStatus deliveryStatus;

    /** Records a confirmed step; a step behind the one already recorded is ignored. */
    public void recordDeliveryStatus(com.fooddelivery.common.enums.DeliveryStatus confirmed) {
        if (confirmed == null) return;
        if (deliveryStatus == null || confirmed.getSequence() > deliveryStatus.getSequence()) {
            deliveryStatus = confirmed;
        }
    }

    /** An assignment only authorises while it is live. */
    public boolean authorises(UUID candidateDriverId) {
        return state == State.ASSIGNED && driverId != null && driverId.equals(candidateDriverId);
    }
}
