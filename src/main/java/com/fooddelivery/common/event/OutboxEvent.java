package com.fooddelivery.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {
    private String id;
    private String aggregateType;
    private String aggregateId;
    private String type;
    private String payload;
    private LocalDateTime createdAt;
}
