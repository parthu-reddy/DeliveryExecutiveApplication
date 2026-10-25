package contracts.messaging

/*
 * order-events / DRIVER_ASSIGNED as OrderAssignmentService.assignedEvent builds it when a rider accepts the ping.
 * occurredAt is the delivery service's clock (fixed at 2026-10-25T09:30:00Z in BaseMessagingClass). order-service,
 * restaurant-service and the kitchen feed consume it.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("a rider accepted the order, with the time it happened")
    label("driver_assigned")
    input {
        triggeredBy('fireDriverAssigned()')
    }
    outputMessage {
        sentTo('order-events')
        headers {
            header('eventId', $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')), consumer('9f4a5b6c-7d8e-4f90-a1b2-c3d4e5f6a7b8')))
            header('eventType', 'DRIVER_ASSIGNED')
            header('aggregateType', 'ORDER')
        }
        body([
            orderId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
            driverId: '4f4a4e37-6ca5-5598-94f1-43ef1628f631',
            driverName: 'Ravi Kumar',
            occurredAt: '2026-10-25T09:30:00Z'
        ])
    }
}
