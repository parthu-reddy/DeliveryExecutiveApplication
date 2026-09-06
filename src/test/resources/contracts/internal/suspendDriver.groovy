package contracts.internal

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("Should suspend driver")
    
    request {
        method 'POST'
        urlPath(value(consumer(regex('/api/v1/internal/delivery/drivers/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/suspend')), producer('/api/v1/internal/delivery/drivers/00000000-0000-0000-0000-000000000000/suspend')))
    }
    
    response {
        status OK()
    }
}
