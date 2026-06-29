package com.fooddelivery.order.repository;

import com.fooddelivery.order.entity.PaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IPaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {
    Optional<PaymentIntent> findByInternalOrderId(UUID internalOrderId);
    Optional<PaymentIntent> findByGatewayOrderId(String gatewayOrderId);
    
    @Query("SELECT p FROM PaymentIntent p WHERE p.status = :status AND p.createdAt < :cutoffTime")
    List<PaymentIntent> findByStatusAndCreatedAtBefore(@Param("status") String status, @Param("cutoffTime") LocalDateTime cutoffTime);
}
