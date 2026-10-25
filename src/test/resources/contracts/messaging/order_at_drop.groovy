package contracts.messaging

/*
 * order-events / ORDER_AT_DROP as AtDropStateStrategy writes it: the rider tapped "I've arrived" at the customer's door.
 * occurredAt is the delivery service's clock (fixed at 2026-10-25T09:30:00Z in BaseMessagingClass). order-service consumes
 * it. The rider's live position is about 600 m from the pin, so the tap is recorded unverified with its distance (D16).
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("the rider reached the door, unverified, with the time and the distance")
    label("order_at_drop")
    input {
        triggeredBy('fireOrderAtDrop()')
    }
    outputMessage {
        sentTo('order-events')
        headers {
            header('eventId', $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')), consumer('8e3f4a5b-6c7d-4e8f-90a1-b2c3d4e5f6a7')))
            header('eventType', 'ORDER_AT_DROP')
            header('aggregateType', 'ORDER')
        }
        body([
            orderId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
            driverId: '4f4a4e37-6ca5-5598-94f1-43ef1628f631',
            occurredAt: '2026-10-25T09:30:00Z',
            verified: false,
            distanceMetres: 600
        ])
    }
}
