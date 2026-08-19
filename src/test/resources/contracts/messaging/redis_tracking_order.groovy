package contracts.messaging

/*
 * Redis Pub/Sub channel "tracking:order:{orderId}", consumed by the customer-facing SSE
 * tracking stream. The payload is a serialized TelemetryEventRequest -- see
 * DeliveryTelemetryController and LocationTrackingWebSocketHandler, both of which publish
 * objectMapper.writeValueAsString(event) to this channel.
 *
 * A colon is not a legal Kafka topic character, so this destination is fed to the message
 * verifier by a direct in-memory publish. That pins the payload schema, not the transport.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("Should publish TelemetryEventRequest to the Redis channel tracking:order:{orderId}")
    label("redis_tracking_order")
    input {
        triggeredBy('fireTelemetryEvent()')
    }
    outputMessage {
        sentTo('tracking:order:7a1d5e90-3c22-4b6f-8a11-9d4c2e77b501')
        body([
            driverId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            lat: 12.971598,
            lng: 77.594562,
            orderId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            speedKmh: 18.5,
            isMockLocation: false,
            timestampMs: $(producer(regex('[0-9]{13}')))
        ])
    }
}
