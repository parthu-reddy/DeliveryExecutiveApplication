package com.fooddelivery.delivery.repository;

import com.fooddelivery.delivery.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IOutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    List<OutboxEventEntity> findByStatusOrderByCreatedAtAsc(String status);
    
    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM outbox_events WHERE status IN :statuses ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEventEntity> findUnprocessedEventsAndLock(@org.springframework.data.repository.query.Param("statuses") List<String> statuses);
}
