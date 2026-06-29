# Delivery Executive Application (Logistics & Tracking)

The Delivery Executive Application is a specialized microservice designed to manage the logistics of food delivery. It handles driver tracking, real-time location streaming via WebSockets, and the dispatch algorithms that assign drivers to orders.

## Key Responsibilities

1. **Logistics Dispatch**: 
   - Listens for accepted orders (`ORDER_ACCEPTED`) and uses the `LogisticsDispatchService` to find nearby drivers.
2. **Real-Time Location Tracking**: 
   - Provides WebSocket endpoints for drivers to stream their live GPS coordinates into the `delivery_db`.
3. **Delivery Lifecycle**:
   - Exposes APIs for drivers to accept pings, pick up orders, and mark them as delivered.
   - Broadcasts driver assignment and delivery completion back to the central orchestrator.

## Architecture & Integrations

- **Database**: PostgreSQL (`delivery_db`). Fully isolated.
- **Message Broker**: Apache Kafka.
- **Events Published**: 
  - `DRIVER_ASSIGNED` -> `order-events` (Consumed by Customer App)
  - `ORDER_DELIVERED` -> `order-events` (Consumed by Customer App for ledger payouts)
  - `DISPATCH_REQUEST` -> `logistics-dispatch` (Consumed by Maps Integration)
- **Events Consumed**:
  - `ORDER_ACCEPTED` (From `order-events` - contains restaurant coordinates for routing)

## Running Locally

```bash
# Start required infrastructure (Kafka, Zookeeper, PostgreSQL)
docker-compose up -d

# Run the application
./mvnw spring-boot:run
```
