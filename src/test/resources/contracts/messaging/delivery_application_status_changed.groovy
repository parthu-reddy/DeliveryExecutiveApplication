package contracts.messaging

org.springframework.cloud.contract.spec.Contract.make {
    description('typed application transition routes through the outbox to delivery-partner-events')
    label('delivery_application_status_changed')
    input { triggeredBy('fireDeliveryApplicationStatusChanged()') }
    outputMessage {
        sentTo('delivery-partner-events')
        headers {
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
