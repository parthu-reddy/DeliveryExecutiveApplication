import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description('service driver summary contains only id and display name')
    request {
        method 'GET'
        url '/api/v1/internal/drivers/123e4567-e89b-12d3-a456-426614174000'
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(id: '123e4567-e89b-12d3-a456-426614174000', fullName: 'Test Driver')
    }
}
