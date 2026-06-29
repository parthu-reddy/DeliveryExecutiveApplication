---
name: maps-integration-api
description: API documentation and integration guidelines for the Ola Maps Integration service. Use this skill when building other microservices, frontends, or agents that need to consume map routing, autocomplete, or dispatch data.
---

# Ola Maps Integration: API & Integration Guide

This skill provides integration details for interacting with the mapped location and routing functionalities now built directly into the Food Delivery Modular Monolith.

## Base URL
Default local environment runs on: `http://localhost:8080`

## REST API Endpoints

### 1. Places Autocomplete
**Endpoint**: `GET /api/v1/places/autocomplete`
**Purpose**: Fetch predictive search text for street addresses.
**Query Parameters**:
- `input` (String, required): The user's typed input.
**Response**: Standard Ola Maps predictions array.

### 2. Reverse Geocoding
**Endpoint**: `GET /api/v1/places/reverse-geocode`
**Purpose**: Convert precise map pins into human-readable text.
**Query Parameters**:
- `lat` (Double, required): Latitude
- `lng` (Double, required): Longitude
**Response**: `{ "success": true, "data": "123 Main St, City", ... }`

### 3. Order Dispatch
**Internal Service**: `LogisticsDispatchService.dispatchNearestDriver(lat, lng, orderId)`
**Purpose**: Automatically locate the nearest available driver using Redis Geospatial indexes and atomic locking. Note that this is no longer a REST endpoint, but an internal service triggered via the Saga Outbox pattern when an order is accepted by a kitchen.

### 4. Turn-by-Turn Routing
**Endpoint**: `GET /api/v1/logistics/route`
**Purpose**: Retrieve directions and polyline coordinates to render a route on a map interface.
**Query Parameters**:
- `sourceLat` (Double, required)
- `sourceLng` (Double, required)
- `destLat` (Double, required)
- `destLng` (Double, required)

### 5. Set Driver Availability
**Endpoint**: `POST /api/delivery/status`
**Purpose**: Mark a driver as available (e.g., clocked in, no active order) or unavailable.
**Body (JSON)**:
```json
{
  "driverId": "uuid-of-driver",
  "available": true
}
```

## WebSocket Integration (Driver Telemetry)

For driver apps continually broadcasting their GPS location.

**Endpoint**: `ws://localhost:8080/tracking`
**Protocol**: Standard WebSockets (Text)

**Payload Format (JSON)**:
Clients should send this payload rapidly (e.g., every 3-5 seconds).
```json
{
  "driverId": "uuid-of-driver",
  "lat": 12.9715987,
  "lng": 77.5945627
}
```
*Note: The server uses Project Reactor Sinks to aggregate and flush these to Redis Geospatial indexes efficiently.*
