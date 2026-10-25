package com.fooddelivery.delivery.repository;

import com.fooddelivery.delivery.entity.OrderReadiness;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrderReadinessRepository extends JpaRepository<OrderReadiness, UUID> {
}
