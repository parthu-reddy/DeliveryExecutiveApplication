package com.fooddelivery.delivery.repository;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface IDeliveryExecutiveRepository extends JpaRepository<DeliveryExecutive, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT d FROM DeliveryExecutive d WHERE d.id = :id")
    java.util.Optional<DeliveryExecutive> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);
    
    java.util.List<DeliveryExecutive> findByStatusAndUpdatedAtBefore(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus status, java.time.LocalDateTime time);
    
    java.util.List<DeliveryExecutive> findByStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus status);
    
    Page<DeliveryExecutive> findByStatus(DeliveryExecutiveStatus status, Pageable pageable);
    
    java.util.Optional<DeliveryExecutive> findByPhoneNumber(String phoneNumber);
}
