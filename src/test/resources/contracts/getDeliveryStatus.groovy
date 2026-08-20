package contracts
import org.springframework.cloud.contract.spec.Contract
Contract.make {
    // NOT YET IMPLEMENTED. This documents the endpoint ONDC needs; there is no such route today.
    // ignored() makes Spring Cloud Contract generate a @Disabled test, so the contract survives as a
    // specification without producing a false green or a red build.
    // See ONDCIntegrationService/UNIMPLEMENTED_FOR_ONDC/.
    ignored()
    request {
        method 'GET'
        urlPath('/api/v1/delivery/orders/1/status')
    }
    response {
        status 200
        headers {
            contentType(applicationJson())
        }
        body([
            status: "ASSIGNED"
        ])
    }
}
