package com.fooddelivery.order.repository;

import com.fooddelivery.order.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ILedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    boolean existsByTransactionId(UUID transactionId);
}
