package contracts.messaging

org.springframework.cloud.contract.spec.Contract.make {
    description("Should send platform.logistics.dispatch events")
    label("logistics_dispatch")
    input {
        triggeredBy('fireLogisticsDispatch()')
    }
    outputMessage {
        sentTo('platform.logistics.dispatch')
        body([
            eventId: "log-444",
            type: "DISPATCH_ASSIGNED",
            payload: [
                orderId: 1001,
                executiveId: "exec-777"
            ]
        ])
    }
}
