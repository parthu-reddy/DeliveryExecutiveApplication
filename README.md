# Food Delivery Modular Monolith

This repository contains the backend for the Food Delivery application, built as a highly cohesive Modular Monolith. The architecture focuses on domain-driven design, decoupling internal domains while keeping the deployment model simple as a single deployable artifact.

## Technologies Used
- Java 21
- Spring Boot 3.3.0
- PostgreSQL (Primary transactional database)
- Redis (Geospatial indexing and distributed locks)
- Flyway (Database migrations)
- Maven (Build tool)
- Docker & Docker Compose (Local environment setup)

## Core Domains
The application is structured into domain modules under `com.fooddelivery`:
- **`common`**: Shared infrastructure, exception handling, custom filters (Idempotency, Request Caching), and API Response structures.
- **`customer`**: Handles authentication, restaurant discovery (PostGIS radius search), and cart/order initiation.
- **`restaurant`**: Manages KYB onboarding (mocked verification), catalogs, and fulfillment lifecycle (`ACCEPTED`, `READY_FOR_PICKUP`).
- **`delivery`**: Driver onboarding, fleet management, logistics dispatching, and real-time WebSocket telemetry for tracking.
- **`order`**: Core orchestrator utilizing the Saga pattern to manage distributed transactions between payments, fulfillment, and logistics. Contains the Ledger service for immutable double-entry accounting.

## Advanced Patterns Implemented
1. **Saga Orchestration**: Orders progress through complex states via `OrderSagaOrchestrator` using a robust event-driven approach.
2. **Transactional Outbox**: Events emitted by domains are stored in the database transactionally alongside state changes, preventing dual-write issues.
3. **Optimistic Locking**: The `LedgerService` ensures atomic credit/debit operations using `@Version` fields on accounts.
4. **Distributed Redis Locks**: `LogisticsDispatchService` implements atomic locking to prevent multiple orders from being assigned to the same delivery executive simultaneously, using `opsForValue().setIfAbsent` and proper lock release lifecycles on delivery completion, cancellation or timeouts.
5. **Idempotency**: Critical endpoints are protected against replay attacks and duplicate requests using an `IdempotencyFilter`.
6. **Strict Kafka Exception Handling**: Listener methods deliberately rethrow exceptions instead of swallowing them, ensuring the Kafka container handles retries and dead-letter queues properly without dropping messages.
7. **Strict Transaction Boundaries**: Resolved AOP proxy bypass issues by inlining self-invoked transactional methods or ensuring they are routed through the proxy, ensuring atomic commits across the system.

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



## Testing
Comprehensive end-to-end testing scripts are provided in the `tests/` directory to simulate complete user journeys, error handling, and theoretical edge cases.

To run the complete test suite:
```bash
./test_all.sh
```

Tests include validations for:
- Happy Path (End to End order creation to delivery)
- Idempotency Validation (Preventing duplicate orders)
- Webhook Authentication (Vyapar HMAC validation)
- Negative Edge Cases (e.g., Driver rejections, Driver Ping timeouts, Driver Delivery Failures, and Restaurant Post-Acceptance Cancellations).

**Note on Testing/Onboarding**: 
- The `test_all.sh` script automatically flushes the Redis store (`DRIVER_LOCATION_KEY`) before running the suite to prevent cross-test driver assignment pollution. 
- When manually hitting endpoints or creating test scripts, be aware that `RestaurantOnboardingService` mandates a "Penny Drop" mock verification. The fields `bankAccountNumber` and `ifscCode` are REQUIRED in the JSON payload to successfully onboard a restaurant (otherwise `400 Bad Request` is returned).

## Getting Started
Ensure Docker is running, then use the provided script to start the local environment (Postgres and Redis):
```bash
docker-compose up -d
```
Then start the application:
```bash
./mvnw spring-boot:run
```
