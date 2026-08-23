package contracts.messaging

/*
 * Real wire payload for platform.logistics.dispatch, from
 * LogisticsDispatchService.dispatchNearestDriver -- a flat map serialized with the app ObjectMapper
 * and keyed by orderId. There is deliberately no eventType/type field. This producer bypasses the
 * outbox on purpose (dispatch latency), but it now sets an eventId header of its own so the
 * consumer's idempotency check has a key. `excludedDriverIds` is omitted when empty.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("Should publish a dispatch request to platform.logistics.dispatch")
    label("logistics_dispatch")
    input {
        triggeredBy('fireLogisticsDispatch()')
    }
    outputMessage {
        sentTo('platform.logistics.dispatch')
        headers {
            header('eventId', $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')), consumer('0f1a5cb3-2b6d-5a1e-9c47-8e3f6d2a1b04')))
        }
        body([
            orderId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            restaurantLat: 12.971598,
            restaurantLng: 77.594562,
            deliveryLat: 12.935242,
            deliveryLng: 77.624400,
            deliveryAddress: "221B Baker Street, Bangalore",
            excludedDriverIds: []
        ])
    }
}
