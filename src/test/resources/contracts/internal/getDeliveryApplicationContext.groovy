import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description('service-only application context contains identity, lifecycle status and version without profile data')
    request {
        method 'GET'
        url '/api/v1/internal/delivery-applications/123e4567-e89b-12d3-a456-426614174000/context'
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(executiveId: '123e4567-e89b-12d3-a456-426614174000', status: 'DRAFT', version: 0)
    }
}
