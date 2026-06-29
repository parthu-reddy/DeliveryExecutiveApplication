package com.fooddelivery.order.repository;

import com.fooddelivery.order.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

public interface IOutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    List<OutboxEventEntity> findTop100ByOrderByCreatedAtAsc();
    
    @Query(value = "SELECT * FROM outbox_events WHERE status IN :statuses ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEventEntity> findUnprocessedEventsAndLock(@Param("statuses") List<String> statuses);
}
