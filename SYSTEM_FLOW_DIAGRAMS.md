# Delivery Executive Application - System Flow Diagrams

This document illustrates the event-driven architecture and sequence flows of the `DeliveryExecutiveApplication`, handling driver logistics, real-time WebSocket location tracking, and order dispatching.

## High-Level Event Architecture

```mermaid
graph TD
    subgraph Delivery Executive Application
        API[Delivery/Logistics REST API]
        WS[Location WebSocket Handler]
        DB[(delivery_db)]
        Consumer[Order Event Consumer]
        Dispatch[Logistics Dispatch Service]
    end

    subgraph Kafka Message Broker
        T1(order-events)
        T2(logistics-dispatch)
    end

    subgraph External Microservices
        RestApp[Restaurant Application]
        CustApp[Customer Application]
        MapsApp[Maps Integration Service]
    end

    RestApp -->|Publishes ORDER_ACCEPTED| T1
    
    T1 -->|Consumed by| Consumer
    Consumer -->|Extracts Lat/Lng| Dispatch
    Dispatch -->|Request Dispatch| T2
    T2 -->|Consumed by| MapsApp
    
    WS -->|Stream Location| DB
    
    API -->|Driver Accepts Ping| Dispatch
    Dispatch -->|Publish DRIVER_ASSIGNED| T1
    
    API -->|Driver Marks Delivered| Dispatch
    Dispatch -->|Publish ORDER_DELIVERED| T1
    
    T1 -->|Consumed by| CustApp
```

## Logistics Dispatch Sequence

```mermaid
sequenceDiagram
    participant K_Order as Kafka (order-events)
    participant Consumer as OrderEventConsumer
    participant Dispatch as LogisticsDispatchService
    participant K_Logistics as Kafka (logistics-dispatch)
    participant Driver as Driver App (WebSocket/API)

    K_Order->>Consumer: Receive ORDER_ACCEPTED (contains Rest Lat/Lng)
    Consumer->>Dispatch: Trigger driver search
    Dispatch->>K_Logistics: Publish DISPATCH_REQUEST (async to MapsService)
    
    note over Driver,Dispatch: Driver receives push/websocket ping
    
    Driver->>Dispatch: POST /api/v1/delivery/accept-ping
    Dispatch->>K_Order: Publish DRIVER_ASSIGNED
    
    note over Driver,Dispatch: Driver picks up & delivers
    Driver->>Dispatch: POST /api/v1/delivery/status (DELIVERED)
    Dispatch->>K_Order: Publish ORDER_DELIVERED
```
