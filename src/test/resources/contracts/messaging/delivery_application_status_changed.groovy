package contracts.messaging

org.springframework.cloud.contract.spec.Contract.make {
    description('typed application transition routes through the outbox to delivery-partner-events')
    label('delivery_application_status_changed')
    input { triggeredBy('fireDeliveryApplicationStatusChanged()') }
    outputMessage {
        sentTo('delivery-partner-events')
        headers {
            header('eventId', $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')), consumer('0f1a5cb3-2b6d-5a1e-9c47-8e3f6d2a1b04')))
            header('eventType', 'DELIVERY_APPLICATION_STATUS_CHANGED')
            header('aggregateType', 'DELIVERY_PARTNER')
        }
        body([
            executiveId: '123e4567-e89b-12d3-a456-426614174000',
            status: 'SUBMITTED', applicationVersion: 3,
            changedAt: '2026-10-04T00:00:00Z'
        ])
    }
}
