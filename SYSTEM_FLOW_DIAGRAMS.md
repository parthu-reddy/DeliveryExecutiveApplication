# Comprehensive System Flow Diagrams

This document contains detailed sequence diagrams for every major scenario within the Food Delivery Application backend.

## Comprehensive Cross-Service Orchestration Map

```mermaid
flowchart TD
    %% Swimlanes via Subgraphs
    subgraph CustomerApp [Customer App]
        direction TB
        C1(Place Order)
        C2(Pay via UPI)
        C3(Receive Notifications)
        C4(Receive Food)
    end

    subgraph FDA [Monolith: OrderSagaOrchestrator]
        direction TB
        O1(Create Order)
        O2{Payment Timeout 15m}
        O3[Cancel Order: Payment Failed]
        O4(Order PAID)
        O5{Restaurant Response}
        O6[Cancel Order: Restaurant Rejected]
        O7(Saga: Dispatch Driver)
        O8{Driver Assigned?}
        O9[Cancel Order: No Driver Available]
        O10{Driver Accepted?}
        O11(Redispatch: Find Next)
        O12{Restaurant Prepares}
        O13[Cancel Order: Cancelled Post-Accept]
        O14{Delivery Status}
        O15[Cancel Order: Delivery Failed]
        O16(Ledger: Credit Rest/Driver)
    end

    subgraph PGI [PaymentGatewayIntegration]
        direction TB
        P1(Vyapar: Generate Intent)
        P2(Webhook: Payment Success)
        P3(Process Refund)
    end

    subgraph Rest [Restaurant Partner]
        direction TB
        R1(Receive Order Alert)
        R2(Accept Order)
        R3(Reject Order)
        R4(Prepare Food)
        R5(Cancel Mid-Prep)
        R6(Handover to Driver)
    end

    subgraph MI [MapsIntegration]
        direction TB
        M1(Search Nearest Fleet)
        M2[DRIVER_ASSIGNED]
        M3[DISPATCH_FAILED]
        M4(Release Driver Lock)
    end

    subgraph DriverApp [Delivery Executive]
        direction TB
        D1(Receive Dispatch Ping)
        D2(Accept Ping)
        D3(Reject/Timeout Ping)
        D4(Pickup Food)
        D5(Deliver Success)
        D6(Delivery Failed)
    end

    subgraph CI [CommunicationIntegration]
        direction TB
        N1(Dispatch Email/SMS/Push)
    end

    %% Routing / Edges
    C1 -->|POST /api/v1/orders| O1
    O1 -->|HTTP POST| P1
    P1 -->|UPI Intent| C2
    O1 --> O2
    O2 -- Timeout --> O3
    
    C2 -->|Vyapar Server| P2
    P2 -->|Kafka: payment-events| O4
    O2 -- Success --> O4
    
    O4 -->|Kafka: order-events OUTBOX| R1
    R1 --> R2
    R1 --> R3
    
    R3 -->|Kafka: order-events| O5
    O5 -- Rejected --> O6
    O6 -->|HTTP POST| P3
    O6 -->|Kafka: notifications| N1
    N1 --> C3
    
    R2 -->|Kafka: order-events| O5
    O5 -- Accepted --> O7
    O7 -->|Kafka: platform.logistics.dispatch| M1
    
    M1 --> M2
    M1 --> M3
    
    M3 -->|Kafka: order-events| O8
    O8 -- DISPATCH_FAILED --> O9
    O9 -->|HTTP POST| P3
    O9 -->|Kafka: notifications| N1
    
    M2 -->|Kafka: order-events DRIVER_ASSIGNED| O8
    O8 -- DRIVER_ASSIGNED --> D1
    
    D1 --> D2
    D1 --> D3
    
    D3 -->|Kafka: order-events ORDER_DRIVER_REJECTED| O10
    O10 -- Rejected --> O11
    O11 --> O7
    O11 -->|HTTP POST| M4
    
    D2 -->|Kafka: order-events ORDER_DRIVER_ACCEPTED| O10
    O10 -- Accepted --> O12
    
    R2 --> R4
    R4 --> R5
    R4 --> R6
    
    R5 -->|Kafka: order-events ORDER_CANCELLED_BY_RESTAURANT| O12
    O12 -- Cancelled --> O13
    O13 -->|HTTP POST| P3
    O13 -->|HTTP POST| M4
    O13 -->|Kafka: notifications| N1
    
    R6 --> D4
    D4 --> D5
    D4 --> D6
    
    D6 -->|Kafka: order-events DELIVERY_FAILED| O14
    O14 -- Failed --> O15
    O15 -->|HTTP POST| P3
    O15 -->|HTTP POST| M4
    O15 -->|Kafka: notifications| N1
    
    D5 -->|Kafka: order-events ORDER_DELIVERED| O14
    O14 -- Success --> O16
    O16 --> C4
    O16 -->|Kafka: notifications| N1
```

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
