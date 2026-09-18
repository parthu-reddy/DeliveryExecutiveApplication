# DeliveryExecutiveApplication

The DeliveryExecutiveApplication handles the onboarding, availability, and active delivery tracking of driver partners.

## Setup & Build
1. Build the service: `mvn clean install`
2. Run the application: `mvn spring-boot:run`
3. Port: `8083`

## Key Responsibilities
- **Driver Onboarding**: Captures driver details (vehicle, license) for verification.
- **Availability Management**: Tracks if a driver is online and available for orders.
- **Order Dispatch**: Connects with `MapsIntegration` to find the nearest driver to a restaurant for a new order.
- **Live Location**: Streams driver GPS coordinates via Kafka for the `CustomerApplication` to consume.

