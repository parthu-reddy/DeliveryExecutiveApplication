
import org.springframework.cloud.contract.spec.Contract

/*
 * Corrected 2026-08-20. This contract asserted `name` and `status: 'AVAILABLE'`, neither of which
 * exists: DeliveryExecutive serialises `fullName`, and DeliveryExecutiveStatus is
 * OFFLINE | ONLINE | ON_DELIVERY. It could never have passed.
 *
 * Nothing caught it because the consumer is untyped -- RestaurantApplication.DeliveryClient returns
 * Map<String, Object>. Its actual reader, FulfillmentService, uses the constant
 * DRIVER_FIELD_FULL_NAME = "fullName", so producer and consumer already agree; only the contract
 * was wrong.
 */
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
                fullName: 'Test Driver',
                status: 'ONLINE'
            ]
        ])
    }
}
