import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description('batch service driver summaries contain only ids and display names')
    request {
        method 'POST'
        url '/api/v1/internal/drivers/summaries'
        headers { contentType applicationJson() }
        body(['123e4567-e89b-12d3-a456-426614174000'])
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body([[id: '123e4567-e89b-12d3-a456-426614174000', fullName: 'Test Driver']])
    }
}
