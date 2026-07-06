---
name: understand-delivery-service
description: Architectural overview and troubleshooting guide for the Delivery Application. Use this skill when investigating order dispatch logic or telemetry flow.
---

# Understand DeliveryService

The DeliveryService manages the state of the delivery executive (Available, Busy, Offline) and broadcasts their location to Kafka.

## Architecture & Integration

- **Event Driven State Machine**: The order state transitions from `PREPARED` (emitted by RestaurantApplication) to `DISPATCHED` (when driver accepts) to `DELIVERED` (when driver marks complete). This service drives the latter two states.
- **Maps Integration**: To dispatch an order efficiently, it synchronously queries `MapsIntegration` to calculate the ETA of nearby available drivers to the restaurant's location.

## Troubleshooting

- **Driver Cannot Accept Order**: Ensure the driver's profile is in the `AVAILABLE` state and the order is still in `PREPARED` status.
- **Customer Not Seeing Live Location**: Verify that the driver's device is successfully posting to `/telemetry` and that the Kafka `location-updates` topic is receiving the messages.
- **Maps Feign Error**: If the application fails to fetch ETAs, ensure the `MapsIntegration` service is running and accessible via its internal endpoint.
