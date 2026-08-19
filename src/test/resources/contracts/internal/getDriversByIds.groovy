
import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("should return drivers by ids")
    request {
        method 'POST'
        url '/api/v1/internal/admin/delivery/drivers/batch'
        headers {
            contentType applicationJson()
        }
        body([
            "123e4567-e89b-12d3-a456-426614174000"
        ])
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body([
            [
                id: '123e4567-e89b-12d3-a456-426614174000',
                name: 'Test Driver',
                status: 'AVAILABLE'
            ]
        ])
    }
}
