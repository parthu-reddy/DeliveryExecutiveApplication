package contracts.messaging

/*
 * order-events / ORDER_STATUS_UPDATED (OUT_FOR_DELIVERY) as the rider's pickup writes it: OutForDeliveryStateStrategy
 * builds an OrderStatusUpdatedEvent stamped with the delivery service's clock (BaseMessagingClass fixes it at
 * 2026-10-25T09:30:00Z) and the real OutboxProcessor publishes it. order-service records the pickup time from occurredAt.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("the rider picked the order up: OUT_FOR_DELIVERY with the time it happened")
    label("order_status_updated")
    input {
        triggeredBy('fireOrderStatusUpdated()')
    }
    outputMessage {
        sentTo('order-events')
        headers {
            header('eventId', $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')), consumer('6c1d2e3f-4a5b-4c6d-8e7f-90a1b2c3d4e5')))
            header('eventType', 'ORDER_STATUS_UPDATED')
            header('aggregateType', 'ORDER')
        }
        body([
            orderId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
            status: 'OUT_FOR_DELIVERY',
            occurredAt: '2026-10-25T09:30:00Z'
        ])
    }
}
