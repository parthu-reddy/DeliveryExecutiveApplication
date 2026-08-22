package contracts.messaging

/*
 * Mirrors the real wire payload for order-events / ORDER_STATUS_UPDATED.
 * Produced by DeliveryExecutiveApplication.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("Should publish the serialized ORDER_STATUS_UPDATED event to order-events")
    label("order_status_updated")
    input {
        triggeredBy('fireOrderStatusUpdated()')
    }
    outputMessage {
        sentTo('order-events')
        headers {
            header('eventType', 'ORDER_STATUS_UPDATED')
            header('aggregateType', 'ORDER')
        }
        body([
            eventType: "ORDER_STATUS_UPDATED",
            orderId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            status: "OUT_FOR_DELIVERY",
            pickupOtp: $(producer(regex('[0-9]{4}'))),
            deliveryOtp: $(producer(regex('[0-9]{4}')))
        ])
    }
}
