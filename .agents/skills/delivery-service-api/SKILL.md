---
name: delivery-service-api
description: Complete API reference and integration guide for the Delivery Executive Application. Use this when building frontends, other microservices, or agents that need to onboard drivers, manage availability, accept/reject order pings, update delivery status, or consume real-time telemetry.
---

# Delivery Executive Application: API & Integration Guide

This service manages the delivery driver lifecycle — onboarding, real-time location tracking via WebSockets, order dispatch orchestration via delayed Redis queues, and delivery status updates. It runs as an independent Spring Boot microservice on **port 8082** (default).

## Base URL
Default local environment: `http://localhost:8082`

## REST API Endpoints

### Driver Management

#### 1. Onboard Driver
**Endpoint**: `POST /api/delivery/onboard`
**Purpose**: Register a new delivery executive in the system.
**Body (JSON)**:
```json
{
  "name": "Ravi Kumar",
  "phoneNumber": "9876543210",
  "vehicleType": "BIKE",
  "vehicleNumber": "KA-01-AB-1234"
}
```

#### 2. Set Driver Availability
**Endpoint**: `POST /api/delivery/status`
**Purpose**: Toggle a driver online/offline. When set to available, the driver is added to the geospatial index for dispatch consideration.
**Body (JSON)**:
```json
{
  "driverId": "uuid-of-driver",
  "available": true
}
```

---

### Order Dispatch & Driver Interaction

#### 3. Accept Order Ping
**Endpoint**: `POST /api/delivery/drivers/{driverId}/orders/{orderId}/accept`
**Purpose**: Driver accepts an order assignment. Acquires a Redis lock (`order:driver:lock:{orderId}`) to prevent double acceptance.
**Side Effects**: Publishes `DRIVER_ASSIGNED` to `order-events`.

#### 4. Reject Order Ping
**Endpoint**: `POST /api/delivery/drivers/{driverId}/orders/{orderId}/reject`
**Purpose**: Driver rejects the order ping. Releases the driver lock and triggers a retry to find the next closest driver.
**Side Effects**: Publishes `ORDER_DRIVER_REJECTED` to `order-events`.

#### 5. Update Delivery Status
**Endpoint**: `POST /api/delivery/drivers/{driverId}/orders/{orderId}/status`
**Purpose**: Driver updates the delivery lifecycle status.
**Body (JSON)**:
```json
{
  "status": "PICKED_UP"
}
```
**Allowed status values**: `PICKED_UP`, `OUT_FOR_DELIVERY`, `DELIVERED`, `DELIVERY_FAILED`
**Side Effects**: Publishes corresponding event (`OrderPickedUpEvent`, `OrderDeliveredEvent`, etc.) to `order-events`.

#### 6. Order Ping Timeout
**Endpoint**: `POST /api/delivery/drivers/{driverId}/orders/{orderId}/timeout`
**Purpose**: Called when a driver's acceptance timer expires without response. Equivalent to rejection — releases lock and retries dispatch.

---

### Telemetry

#### 7. Batch Location Update (REST)
**Endpoint**: `POST /api/v1/delivery/telemetry/batch`
**Purpose**: Bulk ingest GPS location updates from multiple drivers (alternative to WebSocket for services that prefer REST).
**Body (JSON)**:
```json
[
  { "driverId": "uuid-1", "lat": 12.9715, "lng": 77.5945 },
  { "driverId": "uuid-2", "lat": 12.9720, "lng": 77.5950 }
]
```

---

### Routing

#### 8. Turn-by-Turn Route
**Endpoint**: `GET /api/v1/logistics/route`
**Purpose**: Get driving directions between two points.
**Query Parameters**:
- `sourceLat` (Double, required)
- `sourceLng` (Double, required)
- `destLat` (Double, required)
- `destLng` (Double, required)

---

## WebSocket Integration (Driver Telemetry)

For driver apps continually broadcasting their GPS location.

**Endpoint**: `ws://localhost:8082/tracking`
**Protocol**: Standard WebSockets (Text)

**Payload Format (JSON)**:
Clients should send this payload every 3-5 seconds.
```json
{
  "driverId": "uuid-of-driver",
  "lat": 12.9715987,
  "lng": 77.5945627
}
```
*The server uses Project Reactor Sinks to aggregate and flush locations to Redis Geospatial indexes (`GEOADD driver_locations`) efficiently via `.bufferTimeout()`.*

---

## Kafka Integration

### Consumed Events (from `order-events`)
| Event | Action |
|---|---|
| `ORDER_ACCEPTED` | Calculates `dispatchTime = (now + prepTime) - 15min` and adds to Redis delayed dispatch queue (`ZADD delayed_dispatch_queue`) |
| `ORDER_DRIVER_REJECTED` | Self-consumes to trigger next driver dispatch retry |
| `ORDER_REJECTED` | `TerminalStateStrategy`: cleans up Redis locks and cancels pending dispatch |
| `ORDER_CANCELLED_BY_RESTAURANT` | `TerminalStateStrategy`: removes order from delayed dispatch queue, releases locked driver |
| `ORDER_DELAY_REJECTED` | `TerminalStateStrategy`: aborts any pending dispatch and releases driver |
| `ORDER_CANCELLED` | `TerminalStateStrategy`: cleans up all dispatch state |
| `DELIVERY_FAILED` | `TerminalStateStrategy`: cleans up all dispatch state |
| `DISPATCH_CANDIDATE_FOUND` | Receives candidate from MapsIntegration, pings the driver |

### Published Events (to `order-events`)
| Event | Trigger |
|---|---|
| `DRIVER_ASSIGNED` | Driver accepts an order |
| `ORDER_DRIVER_REJECTED` | Driver rejects or times out |
| `OrderPickedUpEvent` | Driver marks pickup |
| `OrderDeliveredEvent` | Driver marks delivery complete |

### Consumed Events (from `platform.logistics.dispatch`)
| Event | Action |
|---|---|
| Dispatch requests | Triggers `MapsIntegration` to find and assign the nearest driver |

## Background Jobs

### DelayedDispatchPoller
- Runs every **1 minute**.
- Scans Redis ZSET `delayed_dispatch_queue` for orders whose `dispatchTime ≤ now`.
- Removes them from the queue and publishes dispatch requests to `platform.logistics.dispatch` for the MapsIntegration service to process.

### DriverPingTimeoutPoller
- Runs every **10 seconds**.
- Scans Redis ZSET `order:ping:timeouts` for driver pings that have expired (timeout limit: 30 seconds).
- Triggers auto-rejection via `DeliveryService.handleTimeout`, releasing the driver lock and retrying dispatch.

## Database
- **PostgreSQL** database: `delivery_db`
- **Flyway migrations**: `src/main/resources/db/migration/`
- Key tables: `delivery_executives`, `delivery_assignments`, `outbox_events`

## State Machine
Driver states use a State Pattern:
- `OfflineState` → `OnlineState` → `AssignedState` → `EnRouteToPickupState` → `DeliveringState` → `OnlineState`
- Terminal events (`ORDER_REJECTED`, `ORDER_CANCELLED_BY_RESTAURANT`, `ORDER_DELAY_REJECTED`, `DELIVERY_FAILED`, `ORDER_CANCELLED`) are handled by `TerminalStateStrategy` which releases all Redis locks and cleans up the dispatch queue.
