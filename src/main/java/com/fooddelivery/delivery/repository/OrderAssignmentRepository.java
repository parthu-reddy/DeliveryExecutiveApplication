package com.fooddelivery.delivery.repository;

import com.fooddelivery.delivery.entity.OrderAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderAssignmentRepository extends JpaRepository<OrderAssignment, UUID> {

    Optional<OrderAssignment> findByOrderId(UUID orderId);

    /**
     * The assignment that authorises this driver to act on this order.
     *
     * <p>Deliberately returns an Optional that the caller must treat as a denial when empty. The
     * guard this replaces short-circuited on a missing record and allowed the update through.
     */
    Optional<OrderAssignment> findByOrderIdAndDriverId(UUID orderId, UUID driverId);
}
