---
name: code-flow-architecture
description: Explains the architecture, logistics, and real-time tracking code flow in the Delivery Executive Application.
---

# Code Flow & Architecture Guide (Delivery Executive Application)

This document describes the architectural patterns used in the Delivery Executive Application.

## Architecture (Microservice)

The backend has been refactored from a Modular Monolith into independent microservices. This application (DeliveryExecutiveApplication) runs on Java 21 and Spring Boot 3.3.0.

The codebase is organized under `com.fooddelivery`:
- **`delivery`**: Manages fleet availability (`DeliveryExecutiveController`), dispatch initiation (`LogisticsDispatchService`), routing integration (`LogisticsController`), and reactive WebSocket real-time telemetry using Project Reactor Sinks and Redis Geospatial (`LocationTrackingWebSocketHandler`).
- **`consumer`**: Contains `OrderEventConsumer` that listens to `ORDER_ACCEPTED` from Kafka.

**Key Engineering Standards:**
- **Database**: The database schema is initialized using Flyway (`delivery_db`). All initial tables and production indexes are squashed into a single `V1__init_schema.sql` file.
- **Production Hardening**: The system uses strict Redis connection lifecycle management to prevent leaks under load.
- **Test Isolation**: E2E tests MUST clear the Redis geospatial index (`DRIVER_LOCATION_KEY`) before running (e.g., using `redis-cli FLUSHALL`) to prevent cross-test driver dispatching conflicts.

## Dispatch & Tracking Flow
1. **Receive Order**: `OrderEventConsumer` listens for `ORDER_ACCEPTED` (which contains restaurant lat/lng).
2. **Dispatch Initiation**: `LogisticsDispatchService` is triggered to find a driver. It utilizes Redis geospatial queries and atomic locks (`driver_lock:{driverId}`) to avoid double assignments.
3. **Driver Assignment**: Emits `DRIVER_ASSIGNED` back to `order-events`.
4. **Real-time Tracking**: WebSockets (`LocationTrackingWebSocketHandler`) accept continuous stream of {lat, lng} from driver apps, buffered into Redis using Project Reactor Sinks.
5. **Completion**: API is called by the driver, emitting `ORDER_DELIVERED`.
6. **Cancellation/Rejection Interception**: Listens to terminal states (`ORDER_REJECTED`, `ORDER_CANCELLED_BY_RESTAURANT`, `ORDER_DELAY_REJECTED`, `DELIVERY_FAILED`, `ORDER_CANCELLED`) to abort any pending dispatches and release locked drivers via `TerminalStateStrategy`.

For visual diagrams, see `Deployment/flow_diagram.md` and `README.md` in the repository root.
