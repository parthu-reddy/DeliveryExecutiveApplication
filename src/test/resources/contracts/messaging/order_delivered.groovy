package contracts.messaging

/*
 * order-events / ORDER_DELIVERED as DeliveredStateStrategy writes it: the customer's delivery OTP was accepted.
 * occurredAt is the delivery service's clock (fixed at 2026-10-25T09:30:00Z in BaseMessagingClass); order-service records
 * deliveredAt from it, never from its own receive time.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("the order was delivered, with the time it happened")
    label("order_delivered")
    input {
        triggeredBy('fireOrderDelivered()')
    }
    outputMessage {
        sentTo('order-events')
        headers {
            header('eventId', $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')), consumer('8e3f4a5b-6c7d-4e8f-90a1-b2c3d4e5f6a7')))
            header('eventType', 'ORDER_DELIVERED')
            header('aggregateType', 'ORDER')
        }
        body([
            orderId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
            status: 'DELIVERED',
            occurredAt: '2026-10-25T09:30:00Z'
        ])
    }
}
