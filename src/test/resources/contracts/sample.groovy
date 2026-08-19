package contracts
import org.springframework.cloud.contract.spec.Contract
Contract.make {
    request {
        method 'POST'
        url '/api/v1/internal/delivery/drivers/00000000-0000-0000-0000-000000000000/suspend'
    }
    response {
        status 200
    }
}
