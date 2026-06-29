package com.fooddelivery.order.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventEntity {
    @Id
    private UUID id;
    
    private String aggregateType;
    private String aggregateId;
    @jakarta.persistence.Column(name = "type")
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    
    private LocalDateTime createdAt;
    
    @Builder.Default
    private String status = "UNPROCESSED";
    private LocalDateTime processedAt;
    private String errorMessage;
}
