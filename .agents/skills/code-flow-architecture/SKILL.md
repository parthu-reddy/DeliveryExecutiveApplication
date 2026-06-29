---
name: code-flow-architecture
description: Explains the architecture, Saga patterns, and the event-driven code flow in the Java Food Delivery Backend and Flutter applications. Use this to understand how the services and clients communicate.
---

# Code Flow & Architecture Guide

This document describes the architectural patterns used in the Food Delivery ecosystem, now unified into a single Modular Monolith.

## 1. Backend Architecture (Modular Monolith)

The backend has been refactored from scattered microservices into a highly cohesive **Modular Monolith** running on Java 21 and Spring Boot 3.3.0. This simplifies deployment while maintaining strict domain boundaries.

The codebase is organized under `com.fooddelivery`:
- **`common`**: Contains shared DTOs (`ApiResponse`), custom exceptions, and core infrastructure filters like `IdempotencyFilter` (Redis locking), `RateLimitingService` (Bucket4j), `RequestCachingFilter` (Webhook validation), and `NotificationRouterService` (Kafka).
- **`customer`**: Manages auth (`AuthController`), location discovery (`PlacesController`), restaurant search via PostGIS `ST_DWithin` (`CustomerRestaurantController`), and cart/order initiation (`OrderController`).
- **`restaurant`**: Manages KYB onboarding via mocked external verifications (`RestaurantOnboardingService`), catalog (`CatalogController`), and kitchen fulfillment (`FulfillmentService`).
- **`delivery`**: Manages fleet availability (`DeliveryExecutiveController`), algorithmic nearest-driver dispatch with atomic Redis locks (`LogisticsDispatchService`), routing (`LogisticsController`), and reactive WebSocket real-time telemetry using Project Reactor Sinks and Redis Geospatial (`LocationTrackingWebSocketHandler`).
- **`order`**: Orchestrates distributed Saga transactions (`OrderSagaOrchestrator`), Outbox pattern implementation, Ledger immutable double-entry accounting with optimistic locking (`LedgerService`), and Vyapar constant-time HMAC webhook validation (`WebhookController`).

**Key Engineering Standards:**
- **Repository Interfaces**: All Spring Data JPA repository interfaces must begin with an uppercase `I` (e.g., `ICustomerRepository`, `IOrderRepository`).
- **Database Migrations**: The database schema is initialized using Flyway. All initial tables and production indexes are squashed into a single `V1__init_schema.sql` file.
- **Production Hardening**: The system uses HikariCP for connection pooling, explicit transaction boundaries, and strict Redis connection lifecycle management to prevent leaks under load.
- **Kafka Listener Exception Handling**: Kafka `@KafkaListener` methods must NEVER silently catch and swallow exceptions. They must log and rethrow exceptions (e.g., as `RuntimeException`) to ensure the Spring Kafka container registers the failure, enabling redelivery retries and DLQ routing.
- **Transaction Proxy Bypass**: AOP proxy bypass issues must be avoided. Methods annotated with `@Transactional` cannot be called internally from within the same bean (self-invocation), as the proxy is bypassed and the transaction is ignored. Logic must be inlined or properly routed.
- **Onboarding Requirements**: The `RestaurantOnboardingService` mandates a "Penny Drop" mock verification. The `bankAccountNumber` and `ifscCode` fields are REQUIRED in the JSON payload when hitting the `POST /api/v1/restaurants/onboard` endpoint (returns `400 Bad Request` otherwise).
- **Test Isolation**: E2E tests MUST clear the Redis geospatial index (`DRIVER_LOCATION_KEY`) before running (e.g., using `redis-cli FLUSHALL`) to prevent cross-test driver dispatching conflicts where orders get assigned to drivers left over from previous test runs.

### 1.1 The Transactional Outbox Pattern
To ensure atomic database updates and Kafka event emissions:
1. When domain services (like `CustomerOrderService` or `FulfillmentService`) mutate state, they synchronously insert an event into the `outbox_events` table within the same `@Transactional` boundary using `OrderSagaOrchestrator.saveStateAndEvent()`.
2. A scheduled `OutboxEventPublisher` polls this table.
3. It publishes events to Kafka and deletes the row upon success.

### 1.2 Saga Orchestration & Financial Ledger
1. **Order Creation**: Order is saved (Status: `CREATED`). Outbox event is emitted.
2. **Webhook/Payment Integration**: `WebhookController` handles callbacks, validating HMAC signatures in constant-time.
3. **Logistics Dispatch**: `LogisticsDispatchService` is triggered, utilizing `opsForGeo().radius()` and atomic `setIfAbsent` on Redis to find and lock a delivery executive. This lock has a TTL of 15 minutes.
4. **Kitchen Acceptance**: `FulfillmentService` changes status to `ACCEPTED` and triggers logistics flow via Outbox.
   * If the restaurant cancels *after* acceptance, status becomes `CANCELLED_BY_RESTAURANT`, and any active driver locks are released.
5. **Logistics Completion**: Driver completes delivery (`DELIVERED`) or fails (`DELIVERY_FAILED`). This sets the driver back to `ONLINE` and releases the Redis lock (`driver_lock:{driverId}`) allowing new assignments. Ping timeouts also release the driver lock.
6. **Ledger**: The `LedgerService` uses optimistic locking (`@Version`) to safely credit and debit the restaurant/driver accounts asynchronously.

# Comprehensive System Flow Diagrams

This document contains detailed sequence diagrams for every major scenario within the Food Delivery Application backend.


## Order Saga Decision Flowchart

```mermaid
flowchart TD
    Start([Customer Places Order]) --> WaitPayment{Payment within 15m?}
    
    WaitPayment -- YES --> RestAccept{Restaurant Accepts?}
    WaitPayment -- NO --> CancelPayment[Cancel Order: Payment Timeout]
    
    RestAccept -- YES --> FindDriver[Find Nearest Driver]
    RestAccept -- NO --> Refund1[Issue Refund] --> CancelRest[Cancel Order: Restaurant Rejected]
    
    FindDriver --> DriverFound{Driver Found?}
    DriverFound -- YES --> Dispatch[Dispatch to Driver]
    DriverFound -- NO --> Refund2[Issue Refund] --> CancelNoDriver[Cancel Order: No Drivers Available]
    
    Dispatch --> DriverAccept{Driver Accepts?}
    DriverAccept -- YES --> Prep[Restaurant Prepares Food]
    DriverAccept -- NO / Timeout --> FindNext[Find Next Nearest Driver] --> DriverFound
    
    Prep --> RestCancel{Restaurant Cancels?}
    RestCancel -- YES --> Refund3[Issue Refund] --> ReleaseLock1[Release Driver Lock] --> CancelPost[Cancel Order: Cancelled Post-Accept]
    RestCancel -- NO --> Pickup[Driver Picks Up]
    
    Pickup --> Deliver{Delivery Successful?}
    Deliver -- YES --> LedgerRest[Credit Restaurant] --> LedgerDriver[Credit Driver] --> Complete([Order Delivered])
    Deliver -- NO --> Refund4[Issue Refund] --> ReleaseLock2[Release Driver Lock] --> CancelFail[Cancel Order: Delivery Failed]
```

## 1. Happy Path: E2E Order to Delivery Flow

```mermaid
sequenceDiagram
    participant C as Customer
    participant API as OrderController
    participant COS as CustomerOrderService
    participant PG as PaymentGatewayOrchestrator
    participant DB as PostgreSQL (Outbox/Orders)
    participant WH as WebhookController
    participant K as Kafka Topics
    participant SAGA as OrderSagaOrchestrator
    participant REST as FulfillmentController
    participant DISP as LogisticsDispatchService
    participant REDIS as Redis (Geospatial/Locks)
    participant LEDG as DoubleEntryLedgerService

    %% 1. Order Creation
    C->>API: POST /api/v1/orders
    API->>COS: createOrderWithPayment()
    COS->>PG: generateUpiIntent()
    PG-->>COS: intent string
    COS->>DB: Save Order (CREATED) & PaymentIntent (INITIATED)
    COS-->>API: OrderResponse + UPI Intent
    API-->>C: 200 OK
    
    %% 2. Payment Success
    Note over C, WH: Customer pays via Vyapar UPI
    Vyapar->>WH: POST /api/v1/webhooks/payment
    WH->>WH: Verify HMAC Signature
    WH->>K: Publish to 'payment-events'
    K-->>SAGA: Consume PaymentSucceededEvent
    SAGA->>DB: Update Order (PAID)
    SAGA->>DB: Save OutboxEvent (ORDER_PAID)
    
    %% 3. Restaurant Fulfillment
    DB-->>K: OutboxPoller -> 'order-events'
    Note over REST: Restaurant receives ORDER_PAID notification
    REST->>DB: POST /accept -> Save OutboxEvent (ORDER_ACCEPTED)
    DB-->>K: OutboxPoller -> 'order-events'
    K-->>SAGA: Consume ORDER_ACCEPTED
    
    %% 4. Logistics Dispatch
    SAGA->>DISP: dispatchNearestDriver(lat, lng, orderId)
    DISP->>REDIS: GEORADIUS (Find nearest drivers)
    DISP->>REDIS: SETNX (Acquire Driver Lock)
    REDIS-->>DISP: Lock Acquired (Driver UUID)
    DISP-->>SAGA: Return Driver UUID
    SAGA->>DB: Update Order (DISPATCHED, driverId)
    
    %% 5. Delivery & Accounting
    Note over C, REST: Driver picks up & delivers food
    Driver->>API: POST /delivered (DeliveryService)
    API->>DB: Update Order (DELIVERED) & Save OutboxEvent (ORDER_DELIVERED)
    DB-->>K: OutboxPoller -> 'order-events'
    K-->>SAGA: Consume ORDER_DELIVERED
    
    %% Ledger Transactions with Deterministic UUIDs
    SAGA->>LEDG: recordTransaction(REST_PAYOUT_<id>)
    LEDG->>DB: existsByTransactionId ? (Idempotency Check)
    LEDG->>DB: Credit Restaurant Account (80%)
    SAGA->>LEDG: recordTransaction(DRIVER_PAYOUT_<id>)
    LEDG->>DB: Credit Driver Account (Flat fee)
```

## 2. Order Cancellation: Payment Timeout (Cron Job)

```mermaid
sequenceDiagram
    participant Cron as @Scheduled Cron
    participant PG as PaymentGatewayOrchestrator
    participant DB as PostgreSQL
    participant POL as OutboxEventPoller
    participant K as Kafka 'order-events'
    participant NOTIF as Notification Service

    Cron->>PG: reconcileStuckPayments()
    PG->>DB: Find INITIATED intents > 15 mins
    PG->>DB: Update Order (CANCELLED) & Intent (FAILED)
    PG->>DB: Save OutboxEvent (ORDER_CANCELLED_PAYMENT_TIMEOUT)
    
    POL->>DB: Poll UNPROCESSED events
    POL->>K: Publish ORDER_CANCELLED_PAYMENT_TIMEOUT
    POL->>DB: Mark event PROCESSED
    
    K-->>NOTIF: Consume Event
    NOTIF->>Customer: Send SMS/Push "Payment failed, order cancelled"
```

## 3. Restaurant Rejection & Refund Flow

```mermaid
sequenceDiagram
    participant REST as Restaurant
    participant API as FulfillmentController
    participant DB as PostgreSQL (Outbox)
    participant K as Kafka 'order-events'
    participant SAGA as OrderSagaOrchestrator
    
    REST->>API: POST /reject
    API->>DB: Update Order (CANCELLED)
    API->>DB: Save OutboxEvent (ORDER_REJECTED)
    
    DB-->>K: OutboxPoller -> 'order-events'
    K-->>SAGA: Consume ORDER_REJECTED
    
    SAGA->>SAGA: processRefund(order)
    SAGA->>DB: Find PaymentIntent (SUCCESS)
    SAGA->>DB: Update PaymentIntent (REFUNDED)
    SAGA->>DB: DoubleEntryLedger: Refund to Customer (Idempotent: REFUND_<id>)
```

## 4. Restaurant Cancellation Post-Acceptance

```mermaid
sequenceDiagram
    participant REST as Restaurant
    participant API as FulfillmentController
    participant DB as PostgreSQL
    participant K as Kafka 'order-events'
    participant SAGA as OrderSagaOrchestrator
    participant DISP as LogisticsDispatchService
    participant REDIS as Redis
    
    REST->>API: POST /cancel (After acceptance)
    API->>DB: Update Order (CANCELLED_BY_RESTAURANT)
    API->>DB: Save OutboxEvent (ORDER_CANCELLED_BY_RESTAURANT)
    
    DB-->>K: OutboxPoller
    K-->>SAGA: Consume ORDER_CANCELLED_BY_RESTAURANT
    
    %% Release lock & Refund
    SAGA->>DISP: releaseDriverLock(driverId)
    DISP->>REDIS: DEL idempotency:driver_lock:{id}
    SAGA->>SAGA: processRefund(order)
    SAGA->>DB: Update PaymentIntent (REFUNDED)
    SAGA->>DB: Record Ledger Refund (Idempotent: REFUND_<id>)
```

## 5. Driver Rejection & Redispatch Flow

```mermaid
sequenceDiagram
    participant DRV as Driver
    participant API as DeliveryController
    participant DB as PostgreSQL
    participant K as Kafka 'order-events'
    participant SAGA as OrderSagaOrchestrator
    participant DISP as LogisticsDispatchService
    participant REDIS as Redis

    DRV->>API: POST /reject
    API->>DISP: releaseDriverLock(driverId)
    DISP->>REDIS: DEL idempotency:driver_lock:{id}
    API->>DB: Save OutboxEvent (ORDER_DRIVER_REJECTED)
    
    DB-->>K: OutboxPoller
    K-->>SAGA: Consume ORDER_DRIVER_REJECTED
    
    SAGA->>DB: Check if Order is still DISPATCHED
    SAGA->>DISP: dispatchNearestDriver(lat, lng)
    DISP->>REDIS: GEORADIUS & SETNX (Find Next Driver)
    
    alt Next Driver Found
        DISP-->>SAGA: Returns newDriverId
        SAGA->>DB: Update Order (deliveryExecutiveId = newDriverId)
    else No Drivers Found
        DISP-->>SAGA: Returns null
        SAGA->>DB: Revert Order Status to ACCEPTED (Wait for retry)
    end
```

## 6. Delivery Failed Flow

```mermaid
sequenceDiagram
    participant DRV as Driver
    participant API as DeliveryController
    participant DB as PostgreSQL
    participant K as Kafka 'order-events'
    participant SAGA as OrderSagaOrchestrator
    
    DRV->>API: POST /failed (e.g. Customer unavailable)
    API->>DB: Update Order (DELIVERY_FAILED)
    API->>DB: Save OutboxEvent (ORDER_STATUS_UPDATED: DELIVERY_FAILED)
    
    DB-->>K: OutboxPoller
    K-->>SAGA: Consume ORDER_STATUS_UPDATED (JSON status = DELIVERY_FAILED)
    
    SAGA->>SAGA: processRefund(order)
    SAGA->>DB: Update PaymentIntent (REFUNDED)
    SAGA->>DB: Record Ledger Refund (Idempotent: REFUND_<id>)
```

## 7. Reactive Delivery Telemetry Ingestion (WebSockets)

```mermaid
sequenceDiagram
    participant DRV as Driver App
    participant WS as LocationTrackingWebSocketHandler
    participant SINK as Reactor Sinks.Many
    participant FLUX as Flux (Batched)
    participant REDIS as Redis Geospatial

    loop Every 5 Seconds
        DRV->>WS: WebSocket TextMessage {lat, lng}
    end
    
    WS->>SINK: tryEmitNext(LocationEvent)
    
    %% Reactive Backpressure & Buffering
    SINK->>FLUX: onBackpressureBuffer()
    FLUX->>FLUX: bufferTimeout(50 items, 1 second)
    
    FLUX->>REDIS: Pipeline execute (Batch GEOADD)
    Note right of FLUX: High-throughput ingestion avoiding DB bottlenecks
```

## 8. Parallelized Restaurant Onboarding Flow

```mermaid
sequenceDiagram
    participant Admin as Admin/Owner
    participant API as RestaurantOnboardingController
    participant SVC as RestaurantOnboardingService
    participant EXT as External Gov APIs (FSSAI, GSTIN, PAN, CIN, Bank)
    participant DB as PostgreSQL

    Admin->>API: POST /onboard
    API->>SVC: onboardRestaurant()
    
    %% Parallel Execution
    SVC->>EXT: CompletableFuture.supplyAsync(verifyFssai)
    SVC->>EXT: CompletableFuture.supplyAsync(verifyGstin)
    SVC->>EXT: CompletableFuture.supplyAsync(verifyPan)
    SVC->>EXT: CompletableFuture.supplyAsync(verifyCin)
    SVC->>EXT: CompletableFuture.supplyAsync(verifyPennyDrop)
    
    Note over SVC, EXT: All 5 external network calls execute concurrently
    
    EXT-->>SVC: CompletableFuture.allOf().join()
    
    SVC->>DB: Check PennyDrop name match
    SVC->>DB: Save Restaurant & Owner details
    SVC->>DB: Create Ledger Account for Restaurant
    
    API-->>Admin: 200 OK (Restaurant Activated)
```



## 2. Frontend Architecture (Flutter)
*(Note: As per requirements, UI implementation was paused, but the target architecture remains MapLibre GL with reactive Provider state and SQLite offline buffering for resilient tracking).*
