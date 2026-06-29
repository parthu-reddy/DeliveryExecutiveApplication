---
name: notification-service-developer
description: Deep context on the Centralized Notification Service architecture, patterns, and best practices. Use this when fixing bugs or adding new features (like new providers) to the notification service.
---

# Notification Service Developer Guide (Monolith)

You are working on the Notification components, built directly into the Spring Boot 3.x and Java 21 Modular Monolith.

## Architecture
- **Event-Driven**: The system relies on Kafka. `NotificationRouterService` dispatches messages to the `platform.notifications.dispatch` topic. A consumer within the monolith processes these messages asynchronously.
- **Cache / Rate Limiting**: Redis is used via Bucket4j to rate limit notifications globally within `RateLimitingService`.

## Core Functionality & Event Flow

When a `NotificationRequestEvent` is received via Kafka, the monolith executes the following workflow:

1. **Event Ingestion**: The Kafka listener consumes the message.
2. **Opt-Out Check**: The service checks user preferences. If the user has explicitly opted out of the requested channel (e.g., SMS), the notification is skipped.
3. **Rate Limiting Enforcement**: `RateLimitingService` checks the Redis bucket for the `userId` + `channel`. If the user has exceeded their quota, a `RateLimitExceededException` is thrown.
4. **Provider Routing & Dispatch**: `NotificationRouterService` determines the correct provider (e.g., FCM for Push, AWS SES for Email, Exotel for SMS) and dispatches the formatted message.

## Adding a New Provider
If a user requests adding a new provider (e.g., Twilio for SMS):
1. **Implement Interface**: Create a new service class that implements your provider logic.
2. **Update Router**: Inject the new service into `NotificationRouterService` and route requests to it based on the channel.

## Rate Limiting
- Defined in `RateLimitingService` (`com.fooddelivery.common.filter`).
- Currently uses a Bucket4j configuration with Redis (Lettuce) for distributed enforcement.
- Rate limits are typically applied per `userId` + `channel`. Check for `RateLimitExceededException`.

## Kafka Resiliency
- Uses Spring Kafka `@RetryableTopic` for non-blocking retries on transient errors.
- Exceptions like `UserOptedOutException` or `RateLimitExceededException` are excluded from retries to prevent wasted resources.
