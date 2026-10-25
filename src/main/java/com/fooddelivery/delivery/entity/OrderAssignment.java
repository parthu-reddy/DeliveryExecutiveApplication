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

    /*
     * Where the rider is going, copied at assignment from the dispatch payload (the OrderAcceptedEvent DeliveryExecutive
     * cached when it dispatched). The arrival check reads them from here, never from Redis: that payload has a 24-hour
     * TTL and is deleted when the delivery ends, and Redis is not what a decision is read from (DelayPenalties Phase 2).
     */
    @Column(name = "pickup_lat")
    private Double pickupLat;
    @Column(name = "pickup_lng")
    private Double pickupLng;
    @Column(name = "drop_lat")
    private Double dropLat;
    @Column(name = "drop_lng")
    private Double dropLng;

    /** When the restaurant marked the food ready (OrderReadyEvent.occurredAt). Belongs to the order, not the rider. */
    @Column(name = "ready_at")
    private Instant readyAt;

    /*
     * The current rider's arrivals, on this service's clock, in the transaction that published them. They belong to the
     * rider, so a reassignment clears them (recordAssignment). Verified means a fresh live position within the radius.
     */
    @Column(name = "at_restaurant_at")
    private Instant atRestaurantAt;
    @Column(name = "at_restaurant_verified")
    private Boolean atRestaurantVerified;
    @Column(name = "at_drop_at")
    private Instant atDropAt;
    @Column(name = "at_drop_verified")
    private Boolean atDropVerified;

    /** The rider tapped "Food not ready yet" (READY_CONTESTED); accepted once per rider. */
    @Column(name = "ready_contested_at")
    private Instant readyContestedAt;

    /**
     * The rider's expected arrival at the outlet (T3e), as OrderService computed it from the route at assignment. Copied
     * here by OverdueRiderSweeper the first time OrderService has it; it is written once there, so it never changes.
     */
    @Column(name = "expected_at_restaurant_at")
    private Instant expectedAtRestaurantAt;

    /** OverdueRiderSweeper told the rider they are late for the outlet; it tells them once. */
    @Column(name = "overdue_notified_at")
    private Instant overdueNotifiedAt;

    /** Records a confirmed step; a step behind the one already recorded is ignored. */
    public void recordDeliveryStatus(com.fooddelivery.common.enums.DeliveryStatus confirmed) {
        if (confirmed == null) return;
        if (deliveryStatus == null || confirmed.getSequence() > deliveryStatus.getSequence()) {
            deliveryStatus = confirmed;
        }
    }

    /**
     * Records the current rider's arrival at the outlet or at the door. The first tap wins: a repeated tap keeps the first
     * time and its verification. Any other step records nothing.
     */
    public void recordArrival(com.fooddelivery.common.enums.DeliveryStatus step, Instant at, boolean verified) {
        if (step == com.fooddelivery.common.enums.DeliveryStatus.AT_RESTAURANT && atRestaurantAt == null) {
            atRestaurantAt = at;
            atRestaurantVerified = verified;
        } else if (step == com.fooddelivery.common.enums.DeliveryStatus.AT_DROP && atDropAt == null) {
            atDropAt = at;
            atDropVerified = verified;
        }
    }

    /** A new rider holds the order: what the previous one did at the outlet and the door is not theirs. */
    public void clearRiderSteps() {
        deliveryStatus = null;
        atRestaurantAt = null;
        atRestaurantVerified = null;
        atDropAt = null;
        atDropVerified = null;
        readyContestedAt = null;
        expectedAtRestaurantAt = null;
        overdueNotifiedAt = null;
    }

    /**
     * Ends the assignment's authority; the row stays as the record of who held the order. The only place a row
     * becomes RELEASED. Returns false, changing nothing, when it is not live, so a repeated or late terminal event
     * keeps the first release time.
     */
    public boolean release(Instant at) {
        if (state != State.ASSIGNED) {
            return false;
        }
        state = State.RELEASED;
        releasedAt = at;
        return true;
    }

    /** An assignment only authorises while it is live. */
    public boolean authorises(UUID candidateDriverId) {
        return state == State.ASSIGNED && driverId != null && driverId.equals(candidateDriverId);
    }
}
