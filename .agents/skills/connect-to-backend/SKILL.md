---
name: connect-to-backend
description: Explains how frontend applications or other AI agents should connect to the Java backend APIs and WebSockets.
---

# Connecting to the Backend

This guide outlines how to interact with the Food Delivery Backend Modular Monolith from client applications.

## API Gateway / Routing

The backend has been consolidated into a **Modular Monolith**. 
All core domain APIs run on a single unified port on `localhost:8080`.

### Available REST Endpoints:

#### Customer App
- **Auth**: `POST /api/v1/auth/initiate`, `POST /api/v1/auth/verify`
- **Places**: `GET /api/v1/places/autocomplete`, `GET /api/v1/places/reverse-geocode`
- **Restaurants**: `GET /api/v1/restaurants/nearby` (Radius search)
- **Orders**: `POST /api/v1/orders`

#### Restaurant App
- **Onboarding**: `POST /api/v1/restaurants/onboard` (KYB mocked)
- **Catalog**: `POST /api/v1/restaurants/{restaurantId}/catalog/items`, `GET /api/v1/restaurants/{restaurantId}/catalog/items`
- **Fulfillment**: 
  - `POST /api/v1/restaurants/{restaurantId}/fulfillment/orders/{orderId}/accept`
  - `POST /api/v1/restaurants/{restaurantId}/fulfillment/orders/{orderId}/cancel`

#### Delivery App
- **Fleet**: `POST /api/delivery/status`
- **Logistics**: 
  - `GET /api/v1/logistics/route`
  - `POST /api/v1/delivery/ping/{orderId}/accept`
  - `POST /api/v1/delivery/ping/{orderId}/reject`
  - `POST /api/v1/delivery/ping/{orderId}/timeout`
  - `POST /api/v1/delivery/orders/{orderId}/status` (For `DELIVERED`, `DELIVERY_FAILED`, `OUT_FOR_DELIVERY`)

#### Webhooks
- **Payments**: `POST /api/v1/webhooks/vyapar`

All endpoints are standardized using the `ApiResponse<T>` wrapper.
A successful REST response looks like:
```json
{
  "success": true,
  "message": "Operation successful",
  "data": { ... },
  "timestamp": "2024-05-20T10:15:30"
}
```

## Real-Time Driver Telemetry (WebSockets)

The Delivery Executive app continuously streams its GPS location via WebSockets. The connection is handled using Project Reactor.

**Endpoint**: `ws://localhost:8080/tracking`

**Payload (JSON)**:
```json
{
  "driverId": "d-9876",
  "lat": 12.9715987,
  "lng": 77.5945627
}
```

## Fault Tolerance & Headers

When connecting to internal APIs or triggering critical state changes:
- You **must** include an `Idempotency-Key` (UUID) in your HTTP headers for any POST/PUT requests (handled by the global `IdempotencyFilter`).
- Rate limiting is automatically enforced via `Bucket4j`.
- Webhooks must include the `X-Vyapar-Signature` header for HMAC validation.
