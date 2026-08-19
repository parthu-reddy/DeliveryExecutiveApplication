package contracts
import org.springframework.cloud.contract.spec.Contract
Contract.make {
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
