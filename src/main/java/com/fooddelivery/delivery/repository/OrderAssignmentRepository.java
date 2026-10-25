package com.fooddelivery.delivery.repository;

import com.fooddelivery.delivery.entity.OrderAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderAssignmentRepository extends JpaRepository<OrderAssignment, UUID> {
    java.util.List<OrderAssignment> findByDriverIdAndState(UUID driverId, OrderAssignment.State state);


    Optional<OrderAssignment> findByOrderId(UUID orderId);

    /** Serialises a manual reassignment with a concurrent completion/status update. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM OrderAssignment a WHERE a.orderId = :orderId")
    Optional<OrderAssignment> findLockedByOrderId(@Param("orderId") UUID orderId);

    boolean existsByDriverIdAndStateAndOrderIdNot(
            UUID driverId,
            OrderAssignment.State state,
            UUID orderId);

    /**
     * The assignment that authorises this driver to act on this order.
     *
     * <p>Deliberately returns an Optional that the caller must treat as a denial when empty. The
     * guard this replaces short-circuited on a missing record and allowed the update through.
     */
    Optional<OrderAssignment> findByOrderIdAndDriverId(UUID orderId, UUID driverId);

    /**
     * Live assignments whose food was ready by {@code readyBy} and whose rider has neither reached the outlet nor picked
     * up: OverdueRiderSweeper's candidates. A rider who picked up without tapping Arrived has moved past ASSIGNED.
     */
    @Query("SELECT a FROM OrderAssignment a WHERE a.state = com.fooddelivery.delivery.entity.OrderAssignment.State.ASSIGNED"
            + " AND a.atRestaurantAt IS NULL AND a.readyAt IS NOT NULL AND a.readyAt <= :readyBy"
            + " AND (a.deliveryStatus IS NULL OR a.deliveryStatus = com.fooddelivery.common.enums.DeliveryStatus.ASSIGNED)")
    java.util.List<OrderAssignment> findOverdueCandidates(@Param("readyBy") java.time.Instant readyBy);

    /** When the restaurant marked the order ready, if ORDER_READY has arrived (order_readiness); copied at assignment. */
    @Query("SELECT r.readyAt FROM OrderReadiness r WHERE r.orderId = :orderId")
    Optional<java.time.Instant> readyAtOf(@Param("orderId") UUID orderId);
}
