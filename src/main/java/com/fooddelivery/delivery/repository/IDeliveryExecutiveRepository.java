package com.fooddelivery.delivery.repository;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface IDeliveryExecutiveRepository extends JpaRepository<DeliveryExecutive, UUID> {
}
