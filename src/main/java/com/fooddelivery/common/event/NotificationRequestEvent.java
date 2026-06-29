package com.fooddelivery.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequestEvent {
    private UUID userId;
    private String channel; // e.g., "SMS", "PUSH", "EMAIL"
    private String templateCode;
    private Map<String, String> templateParams;
    private String explicitRecipient;
}
