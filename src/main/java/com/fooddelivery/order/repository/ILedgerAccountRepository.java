package com.fooddelivery.order.repository;

import com.fooddelivery.order.entity.LedgerAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ILedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
    @Lock(LockModeType.OPTIMISTIC)
    Optional<LedgerAccount> findByOwnerIdAndOwnerType(UUID ownerId, String ownerType);
}
