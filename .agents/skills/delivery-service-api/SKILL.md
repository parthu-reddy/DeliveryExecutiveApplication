---
name: delivery-service-api
description: Complete API reference and integration guide for the Delivery Executive Application. Use this when building frontends, other microservices, or agents that need to onboard drivers, manage availability, accept/reject order pings, or consume real-time telemetry.
---

# Delivery Service API

The Delivery Service handles interactions for driver partners.

## Base URL
External requests must go through the ApiGateway: `http://localhost:8080/api/delivery`

## Core Endpoints

### 1. Onboarding
`POST /onboard`
- **Headers**: `Authorization: Bearer <token>`
- **Payload**:
  ```json
  {
    "vehicleNumber": "KA01AB1234",
    "licenseNumber": "DL12345"
  }
  ```

### 2. Telemetry (Location Updates)
`POST /telemetry`
- **Headers**: `Authorization: Bearer <token>`
- **Payload**:
  ```json
  {
    "lat": 12.9716,
    "lon": 77.5946
  }
  ```
- **Note**: This endpoint publishes to the `location-updates` Kafka topic, which is consumed by the `CustomerApplication` for live tracking.

### 3. Accept Order
`POST /orders/accept`
- **Headers**: `Authorization: Bearer <token>`
- **Payload**:
  ```json
  {
    "orderId": "uuid"
  }
  ```
