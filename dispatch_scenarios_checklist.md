# Priority Dispatch — Exhaustive Scenarios & Edge Cases

> **Scope**: Every testable path through the dispatch lifecycle, from `ORDER_ACCEPTED` to
> terminal states (`DRIVER_ASSIGNED`, `PRIORITY_DISPATCH_FAILED`, `ORDER_CANCELLED_*`).
> Derived from source-level audit of all 16 participating files across
> `DeliveryExecutiveApplication` and `MapsIntegration`.

---

## Key Constants (Code References)
| Constant | Value | Source |
|---|---|---|
| Ping Timeout | 60 s | `CandidateFoundStrategy` — TTL on `PREFIX_ORDER_PING_PENDING`, `PREFIX_DRIVER_PENDING_PING` |
| Dispatch Failed Retry Delay | 10 s | `TerminalStateStrategy` — `System.currentTimeMillis() + 10000` |
| Rejection Re-dispatch Delay | 10 s | `OrderDriverRejectedStrategy` — `delayMs = 10_000` |
| Max Failed Cycles (No-Driver) | 5 | `DelayedDispatchPoller` — `failedCycles >= 5` |
| Driver Exclusion Threshold | 5 rejections | `DelayedDispatchPoller` — `count >= 5` |
| Delayed Dispatch Poller Interval | 5 s | `@Scheduled(fixedDelay = 5000)` |
| Ping Timeout Poller Interval | 5 s | `DriverPingTimeoutPoller` — `@Scheduled(fixedDelay = 5000)` |
| Stale Driver Sweep Interval | 60 s | `StaleDriverSweeperDaemon` — `@Scheduled(fixedRate = 60_000)` |
| Lock Reaper Interval | 120 s | `RedisLockReaperTask` — `@Scheduled(fixedRate = 120000)` |
| Max Candidates per Batch | 10 (configurable) | `FleetTrackingService` — `dispatch.max-candidates` |
| Geo Radius | `MAX_DELIVERY_RADIUS_KM` | `FleetTrackingService.dispatchOrder` |
| All TTLs (locks, payloads, pings) | 24 h (locks), 2 h (failed cycles, rejection counts), 60 s (pings) | Various |

---

## Redis Key Map (All Keys Used in Dispatch)
| Key Pattern | Type | Purpose |
|---|---|---|
| `order:dispatch:lock:{orderId}` | STRING | Idempotency guard — prevents duplicate dispatch initiation. Values: `"locked"`, `"CANCELLED"`, driverId |
| `order:dispatchPayload:{orderId}` | STRING (JSON) | Cached dispatch payload (lat/lng, delivery address) for retries |
| `order:driver:lock:{orderId}` | STRING | Acceptance lock — holds the assigned driverId, or `"CANCELLED"` |
| `order:ping:pending:{orderId}` | SET | Set of driverIds currently being pinged for this order |
| `order:ping:timeouts` | ZSET | Sorted set of orderIds with timeout score = `now + 60000` |
| `driver:pending_ping:{driverId}` | STRING | Maps driver → orderId for which they have an active ping |
| `driver:active_order:{driverId}` | STRING | Active order assigned to a driver (TTL 24h) |
| `order:dispatch_failed_cycles:{orderId}` | STRING (int) | Counter of consecutive no-driver failures (TTL 2h) |
| `order:rejected_drivers:{orderId}` | HASH | `{driverId: rejectionCount}` — tracks per-driver rejection count (TTL 2h) |
| `delayed_dispatch_queue` | ZSET | Orders pending re-dispatch, score = dispatch-at timestamp |
| `drivers:available:{cityId}` | SET | Pool of available driver IDs |
| `drivers:geo:{cityId}` | GEO | Geo-indexed driver locations |
| `drivers:status` | HASH | `{driverId: statusName}` — cache of driver DB status |
| `order:restaurantStatus:{orderId}` | STRING | Restaurant-side order status (ACCEPTED→PREPARING→READY) |
| `order:driver:stranded:{orderId}` | STRING | Driver stranded by a cancellation (TTL 24h) |
| `driver_last_ping` | ZSET | Last location update timestamp per driver |
| `dispatch_processing_lock:{orderId}` | STRING | Short-lived processing lock (30s) for poller dedup |
| `lock:pollDelayedDispatches` | STRING | Global poller mutex (4s) |
| `lock:pollPingTimeouts` | STRING | Global ping-timeout poller mutex (4s) |

---

## 1. Dispatch Initiation (ORDER_ACCEPTED → Driver Search)

### 1.1 Happy Path — Immediate Dispatch
- [ ] **Standard Immediate Dispatch**: Restaurant accepts order → `OrderAcceptedStrategy.process()` fires → `estimatedCompletionTime == 0` or already past → `setIfAbsent` acquires `order:dispatch:lock` → payload cached in `order:dispatchPayload` → `logisticsDispatchService.dispatchNearestDriver()` → Kafka `LOGISTICS_DISPATCH_REQUEST` published → `DispatchEventConsumer` in MapsIntegration receives it → `FleetTrackingService.dispatchOrder()` finds candidates → emits `DISPATCH_CANDIDATE_FOUND` via Kafka.

### 1.2 Delayed Dispatch (Scheduled for Later)
- [ ] **Dispatch Scheduled for Future**: `estimatedCompletionTime` is 30 min in the future → `dispatchTime = estimatedCompletionTime - 15min` = 15 min from now → Order added to `delayed_dispatch_queue` with score = `dispatchTime` → Poller picks it up 15 min later → Dispatches normally.
- [ ] **Early Ready Trigger**: Order is scheduled for delayed dispatch (in `delayed_dispatch_queue`) → Restaurant sends `ORDER_READY` before the scheduled time → `OrderStatusUpdatedStrategy` detects the order in `delayed_dispatch_queue` → Acquires `dispatch_processing_lock` → Immediately dispatches and removes from queue.
- [ ] **Early Ready — Lock Contention**: `OrderStatusUpdatedStrategy` and `DelayedDispatchPoller` both try to process the same order at the same instant → Only one acquires `dispatch_processing_lock` → The other skips → No duplicate dispatch.

### 1.3 Idempotency
- [ ] **Duplicate ORDER_ACCEPTED Event**: Kafka retry or outbox replay delivers ORDER_ACCEPTED twice → Second call to `setIfAbsent` on `order:dispatch:lock` returns `false` → Logged as "Duplicate ORDER_ACCEPTED dispatch event ignored" → No second dispatch.
- [ ] **ORDER_ACCEPTED with Missing Coordinates**: Payload has `restaurantLat=0` or `restaurantLng=0` → Logged as warning → No dispatch initiated → Order is stuck (requires manual intervention or data fix).

---

## 2. Candidate Discovery (MapsIntegration — FleetTrackingService)

### 2.1 Normal Candidate Selection
- [ ] **Standard Batch Selection**: 1000 drivers online → `dispatchOrder` does geo-radius query → Filters by `drivers:available:{cityId}` membership → Calls Ola Maps distance matrix API for ETAs → Sorts by driving duration → Returns top `maxCandidates` (10) → Removes those 10 from `drivers:available` to prevent double-dispatch → Emits `DISPATCH_CANDIDATE_FOUND` with all 10 driverIds.
- [ ] **Fewer Candidates Than Max**: Only 3 available drivers within radius → All 3 returned → Batch size is 3, not 10.
- [ ] **Exactly maxCandidates Available**: 10 drivers available, `maxCandidates=10` → All 10 dispatched → Available pool is empty.

### 2.2 Excluded Drivers
- [ ] **Excluded Drivers Filter**: Re-dispatch with `excludedDriverIds = [A, B, C]` → Drivers A, B, C are skipped even if nearby and available → Next nearest non-excluded drivers are returned.
- [ ] **All Nearby Drivers Excluded**: Every driver within geo-radius is in the excluded list → `candidates.isEmpty()` → Returns `null` → `DISPATCH_FAILED` emitted.

### 2.3 Zero Candidates
- [ ] **No Drivers Online Globally**: Zero entries in `drivers:available` → `dispatchOrder` returns `null` → `DISPATCH_FAILED` emitted.
- [ ] **Drivers Online But Out of Radius**: 50 drivers online but all > `MAX_DELIVERY_RADIUS_KM` from restaurant → Geo query returns empty → `DISPATCH_FAILED`.
- [ ] **Drivers in Radius But Not Available**: 10 drivers within geo-radius but none are members of `drivers:available` (e.g., all ON_DELIVERY) → `candidates.isEmpty()` → `DISPATCH_FAILED`.

### 2.4 ETA Evaluation Edge Cases
- [ ] **Ola Maps API Failure (Circuit Breaker)**: External API is down → Resilience4j circuit breaker trips → `evaluateDriverETAsFallback` uses Haversine distance / 400 as estimated duration → Candidates still sorted by fallback ETA → Dispatch continues.
- [ ] **Ola Maps API Partial Failure**: API returns ETAs for only 7 of 10 candidates → Remaining 3 get `duration = 999999.0` → They sort to the bottom → Top 7 with real ETAs are preferred.
- [ ] **Cached ETA Response**: Same origin/destination hash exists in Redis with 30s TTL → Cache hit → No API call → Faster response.

### 2.5 Double-Dispatch Prevention
- [ ] **Atomic Pool Removal**: After selecting 10 candidates → `redisTemplate.opsForSet().remove(availKey, topCandidates)` → Those 10 are atomically removed from available pool → A concurrent dispatch for a different order will NOT see these 10 drivers.
- [ ] **Pool Restoration on Reject/Timeout**: When a driver rejects or times out → `releaseDriverLock()` calls `FleetTrackingService.releaseDriver()` → Driver re-added to `drivers:available` → Available for other orders.

---

## 3. Candidate Ping (DISPATCH_CANDIDATE_FOUND → Driver UIs)

### 3.1 Ping Delivery
- [ ] **All Drivers Pinged**: `CandidateFoundStrategy` receives 10 driverIds → Creates `order:ping:pending:{orderId}` SET with all 10 → Creates `driver:pending_ping:{driverId}` STRING for each → Adds to `order:ping:timeouts` ZSET with score `now+60000` → Sends WebSocket ping (dev) or push notification (prod) to each driver.
- [ ] **Failed Cycles Reset on Candidates Found**: If `order:dispatch_failed_cycles:{orderId}` was previously > 0 → `CandidateFoundStrategy` deletes it → Counter resets to 0 → Prevents premature `PRIORITY_DISPATCH_FAILED`.

### 3.2 Duplicate DISPATCH_CANDIDATE_FOUND Guard
- [ ] **Order Already Locked When Candidates Arrive**: `order:driver:lock:{orderId}` already exists (e.g., driver already accepted, or order cancelled with lock set to "CANCELLED") → `CandidateFoundStrategy` logs "Ignoring DISPATCH_CANDIDATE_FOUND" → No pings sent → Prevents re-pinging for an already-resolved order.

### 3.3 Ping Delivery Failures
- [ ] **Driver Not Connected via WebSocket (Dev Mode)**: Driver's WebSocket session is null or closed → `sendPingToDriver` logs warning "Driver X is not connected via WebSocket" → Ping is lost → Driver will eventually time out → System proceeds with remaining drivers.
- [ ] **Push Notification Failure (Prod Mode)**: `notificationRouterService.routeNotification()` throws exception → That specific driver's ping fails → Other drivers still get pinged → Timed-out driver counts as a rejection after 60s.

---

## 4. Driver Acceptance (Accept Flow)

### 4.1 Successful Acceptance
- [ ] **First Driver Accepts**: Driver 1 taps Accept → `acceptOrderPing()` → Lua `ACCEPT_SCRIPT` atomically checks no `order:driver:lock` exists, verifies driver is in `order:ping:pending` SET → Sets `order:driver:lock = driverId` (TTL 24h) → Returns remaining pinged drivers → DB transaction creates `DRIVER_ASSIGNED` outbox event → Driver status set to `ON_DELIVERY` → Remaining 9 drivers' `driver:pending_ping` keys deleted → Order removed from `order:ping:timeouts` → Driver removed from `drivers:available` → `driver:active_order` set.

### 4.2 Race Conditions — Concurrent Acceptance
- [ ] **Two Drivers Accept Simultaneously**: Driver A and Driver B call `acceptOrderPing` at the same instant → Redis Lua script is atomic — only one can `SET` the lock → Driver A wins → Driver B gets `ALREADY_ACCEPTED` → Only one `DRIVER_ASSIGNED` event.
- [ ] **Three+ Drivers Accept Simultaneously**: Drivers 1, 2, 3 all race → Exactly one wins the Lua lock → Others get `ALREADY_ACCEPTED` → No double assignment.
- [x] **Ghost Driver Bug / Availability Leak (Fixed)**: Driver A wins the race and accepts. PREVIOUSLY: Driver B's pending ping was deleted, but Driver B was never released back to `drivers:available`, leaving them permanently stuck. NOW: `acceptOrderPing` explicitly loops through all OTHER pinged drivers and calls `releaseDriverLock()` for them, restoring them to the available pool.

### 4.3 Accept Failure Scenarios
- [ ] **Accept After Cancellation**: Order is cancelled → `order:driver:lock` set to `"CANCELLED"` → Driver taps Accept → Lua script returns `{'CANCELLED'}` → `IllegalStateException("Order was cancelled.")` → Clean rejection.
- [ ] **Accept After Another Driver Won**: Driver B already accepted → `order:driver:lock` exists with B's ID → Driver A taps Accept → Lua returns `{'ALREADY_ACCEPTED'}` → `IllegalStateException("Order is no longer available.")`.
- [ ] **Accept After Ping Expired/Removed**: Driver was removed from `order:ping:pending` (timed out or rejected) → `SISMEMBER` returns 0 → Lua returns `{'INVALID'}` → `IllegalStateException("Ping expired or invalid.")`.
- [ ] **Accept Script Returns Null**: Redis returns null (connection issue?) → `IllegalStateException("Order is no longer available.")`.

### 4.4 Post-Accept Transaction Failure
- [ ] **DB Transaction Fails After Lua Lock**: Lua script succeeds (lock acquired) → DB `findLockedById` throws, or `outboxEventRepository.save` fails → Catch block deletes `order:driver:lock` → Re-adds all pinged drivers to `order:ping:pending` SET with 60s TTL → Lock released cleanly → Other drivers can still accept or system retries.
- [ ] **Lock Lost to Cancellation Between Lua and DB**: Lua acquires lock → Cancellation event fires concurrently and overwrites lock to "CANCELLED" → Transaction checks `currentLock != driverId` → Throws "Order lock was lost to cancellation" → Aborts cleanly.

---

## 5. Driver Rejection (Explicit Reject)

### 5.1 Normal Rejection
- [ ] **Single Driver Rejects, Others Pending**: Driver 1 rejects → Lua `REJECT_SCRIPT` removes from `order:ping:pending` → Returns `"REJECTED"` (remaining > 0) → `driver:pending_ping:{driver1}` deleted → `order:rejected_drivers` hash incremented for driver1 → `releaseDriverLock()` adds driver1 back to available pool → No re-dispatch yet (other drivers still considering).

### 5.2 Last Rejection in Batch
- [ ] **Last Driver Rejects**: Drivers 1-9 already rejected → Driver 10 rejects → Lua returns `"LAST_REJECT"` → `ORDER_DRIVER_REJECTED` outbox event created → Published to Kafka → `order:ping:timeouts` entry removed → `OrderDriverRejectedStrategy` handles the event → Queues order in `delayed_dispatch_queue` with 10s delay.
- [ ] **Outbox Save Fails on Last Reject**: Transaction to save `ORDER_DRIVER_REJECTED` fails → Catch block re-adds the driver to `order:ping:pending` with 60s TTL → Kafka retry mechanism will re-attempt → Prevents lost re-dispatch trigger.

### 5.3 Rejection After Resolution
- [ ] **Reject After Another Driver Accepted**: Driver A accepted → `order:driver:lock` exists → Driver B rejects → Lua checks lock, returns `"ACCEPTED_ALREADY"` → Driver B is still cleaned up (`driver:pending_ping` deleted, rejection counted) → But no `ORDER_DRIVER_REJECTED` event emitted → No re-dispatch.
- [ ] **Reject When Not in Pending Set**: Driver was already timed out or already rejected → `SREM` returns 0 → Lua returns `"NOT_FOUND"` → Cleanup still runs (delete pending ping, release lock) → No event emitted.

### 5.4 Rejection Counting & Exclusion
- [ ] **Same Driver Rejected < 5 Times**: Driver has been pinged for the same order 3 times across different batches → `order:rejected_drivers` hash has `{driverId: 3}` → Driver is NOT in `excludedDriverIds` → Will be pinged again in next batch.
- [ ] **Same Driver Rejected 5+ Times**: `order:rejected_drivers` hash has `{driverId: 5}` → `DelayedDispatchPoller` checks `count >= 5` → Adds to `excludedDriverIds` → Driver permanently excluded for this order.

---

## 6. Driver Timeout (60s Expiry)

### 6.1 Full Batch Timeout
- [ ] **All 10 Drivers Timeout**: None accept or reject within 60s → `DriverPingTimeoutPoller` runs every 5s → At ~65s mark, finds orderId in `order:ping:timeouts` with expired score → Calls `timeoutOrderPing()` → Lua `TIMEOUT_SCRIPT` reads all members of `order:ping:pending`, deletes the SET → For each timed-out driver: deletes `driver:pending_ping`, increments rejection count, releases driver lock → Emits `ORDER_DRIVER_REJECTED` → Triggers re-dispatch cycle.

### 6.2 Partial Batch Timeout (Some Rejected, Others Timeout)
- [ ] **5 Reject, 5 Timeout**: Drivers 1-5 reject explicitly → Remaining pending set = {6,7,8,9,10} → After 60s, timeout sweeps remaining 5 → If any was `LAST_REJECT` from the explicit rejects, `ORDER_DRIVER_REJECTED` already fired → Timeout finds empty pending set → Returns `EMPTY` → Cleans up timeout entry → No duplicate event.
- [ ] **All Reject Before Timeout**: All 10 explicitly reject → Last reject fires `ORDER_DRIVER_REJECTED` → 60s later, timeout poller checks → Lua finds pending set empty → Returns `EMPTY` → Timeout is a no-op → No duplicate re-dispatch.

### 6.3 Timeout Race Conditions
- [ ] **Accept At Exact Timeout Boundary**: Driver taps Accept at exactly 60s → Two scenarios:
  - (a) Accept Lua executes first → Lock set → Timeout Lua finds lock → Returns `ALREADY_ACCEPTED` → Timeout is no-op → Acceptance wins.
  - (b) Timeout Lua executes first → Pending set deleted → Accept Lua finds driver not in set → Returns `INVALID` → Accept fails → Timeout fires `ORDER_DRIVER_REJECTED` → Re-dispatch begins.
- [ ] **Timeout When Order Already Accepted**: Another driver accepted seconds before timeout → `order:driver:lock` exists → Timeout Lua returns `ALREADY_ACCEPTED` → Deletes orphaned pending set → No re-dispatch.
- [x] **Silent Expiration Bug (Fixed)**: PREVIOUSLY: The `order:ping:pending` TTL was set to 60s, exactly matching the business logic timeout. The UI sequentially polled drivers (e.g. driver 1 rejects after 30s, driver 2 times out at 60s). Because the Redis key naturally expired at exactly 60s, the UI's `/timeout` API and the backend's `DriverPingTimeoutPoller` both ran the Lua script *after* the key had vanished, skipping the `LAST_REJECT` state and swallowing the `ORDER_DRIVER_REJECTED` event entirely (stalling the dispatch indefinitely). NOW: TTLs for pending ping keys are set to 5 minutes, ensuring the key survives long enough for the business logic (which fires at 60s) to evaluate the remaining drivers and emit the necessary re-dispatch events.

---

## 7. Re-dispatch (OrderDriverRejectedStrategy → DelayedDispatchPoller)

### 7.1 Normal Re-dispatch Flow
- [ ] **Standard Re-dispatch Cycle**: `ORDER_DRIVER_REJECTED` consumed → `OrderDriverRejectedStrategy` checks `order:dispatch:lock` is NOT `"CANCELLED"` → Reads cached payload → Adds order to `delayed_dispatch_queue` with score `now + 10000` (10s delay) → 10s later, `DelayedDispatchPoller` picks it up → Reads exclusion list → Calls `dispatchNearestDriver` with `excludedDriverIds` → New batch found → `DISPATCH_CANDIDATE_FOUND` → Cycle repeats.

### 7.2 Dispatch Lock Guard
- [ ] **Re-dispatch After Cancellation**: Order cancelled while batch was active → `order:dispatch:lock` set to `"CANCELLED"` → `ORDER_DRIVER_REJECTED` Kafka event arrives (stale) → Strategy reads lock = "CANCELLED" → Logs "Skipping redispatch" → No action.
- [ ] **Re-dispatch After Force Assign**: Admin force-assigned the order → `order:dispatch:lock` set to `"CANCELLED"` → Same guard triggers → Re-dispatch skipped.

### 7.3 Poller Processing
- [ ] **Poller Acquires Lock**: `DelayedDispatchPoller.pollDelayedDispatches()` → Acquires global `lock:pollDelayedDispatches` (4s TTL) → Scans `delayed_dispatch_queue` for due orders → For each: acquires `dispatch_processing_lock:{orderId}` (30s TTL) → Processes.
- [x] **Poller Processing Lock Leak (Fixed)**: PREVIOUSLY: The poller acquired a 30s `dispatch_processing_lock`, but never explicitly released it after successful processing. This caused 10s delayed re-dispatches to falsely fail the lock check on the next cycle, artificially delaying re-dispatches. NOW: A `finally` block in `DelayedDispatchPoller` guarantees the lock is deleted immediately after processing.
- [ ] **Poller Concurrent Instances**: Two service instances run the poller simultaneously → Only one acquires `lock:pollDelayedDispatches` → Other skips → No duplicate processing.
- [ ] **Poller Cancelled Order Guard**: Order in queue, but `order:dispatch:lock` = "CANCELLED" → Poller reads it → Logs "Order was cancelled while in delayed dispatch queue" → Removes from queue → Skips.

### 7.4 Missing Payload in Queue
- [ ] **Stale Entry (No Payload)**: Order is in `delayed_dispatch_queue` but `order:dispatchPayload:{orderId}` was deleted or expired → Poller reads null payload → Logs "No dispatch payload found for order X. Removing stale entry." → Removes from queue → No crash.
- [ ] **Payload With Zero Coordinates**: Payload exists but `restaurantLat=0` and `restaurantLng=0` → `if (lat != 0.0 && lng != 0.0)` fails → Order silently skipped but still removed from queue → Effectively dropped.

### 7.5 Cached Payload for Retry
- [ ] **Re-dispatch Uses Original Payload**: All retries use `order:dispatchPayload:{orderId}` (cached at ORDER_ACCEPTED time with 24h TTL) → Coordinates never change → Consistent dispatch location across all cycles.

---

## 8. Dispatch Failure Escalation (No Drivers Available)

### 8.1 Single No-Driver Failure
- [ ] **DISPATCH_FAILED (Cycle 1)**: Maps returns no drivers → `DISPATCH_FAILED` emitted → `TerminalStateStrategy` handles:
  1. Does NOT delete `order:dispatchPayload` (only deleted on cancellation, not failure).
  2. Does NOT delete `order:dispatch_failed_cycles` (only increments).
  3. Increments `order:dispatch_failed_cycles:{orderId}` to 1 (TTL 2h).
  4. Adds order to `delayed_dispatch_queue` with score `now + 10000`.
  5. Does NOT set dispatch lock to "CANCELLED" (only for terminal cancellation events).

### 8.2 Repeated No-Driver Failures (The 10s Retry Loop)
- [ ] **Cycles 1-4**: Each cycle: `DISPATCH_FAILED` → increment counter → queue with 10s delay → poller picks up → checks `failedCycles < 5` → re-dispatches → Maps still finds no drivers → `DISPATCH_FAILED` again → repeat.
- [ ] **Cycle 5 (Exhaustion)**: `failedCycles` reaches 5 → Poller intercepts: does NOT re-dispatch → Removes from queue → Deletes `dispatch_failed_cycles` counter → Emits `PRIORITY_DISPATCH_FAILED` to Kafka → `TerminalStateStrategy` handles this as a terminal event (cancellation-like cleanup).

### 8.3 Intermittent Driver Availability
- [ ] **Driver Comes Online at Cycle 4**: `dispatch_failed_cycles = 4` → Poller re-dispatches → Maps finds a driver this time → `DISPATCH_CANDIDATE_FOUND` → `CandidateFoundStrategy` deletes `order:dispatch_failed_cycles` (resets to 0) → If that batch also fails, counter starts from 0 again → Driver gets a fresh 5-cycle window.
- [ ] **Critical**: Counter reset happens in `CandidateFoundStrategy`, NOT in the poller → Only resets when actual candidates are found, not on re-dispatch attempt.

### 8.4 Failed Cycles Counter TTL
- [ ] **Stale Counter Cleanup**: `order:dispatch_failed_cycles` has 2h TTL → If order is abandoned without explicit cancellation, counter auto-expires → No permanent memory leak.

---

## 9. Terminal States & Cancellation

### 9.1 Customer Cancellation During Active Ping
- [ ] **Customer Cancels While 10 Drivers Ringing**: `ORDER_CANCELLED_BY_CUSTOMER` → `TerminalStateStrategy`:
  1. Deletes `order:dispatchPayload`.
  2. Deletes `order:rejected_drivers`.
  3. Deletes `order:dispatch_failed_cycles`.
  4. Removes from `delayed_dispatch_queue`.
  5. Reads ALL members of `order:ping:pending` BEFORE deleting the set.
  6. Deletes `driver:pending_ping` for each of the 10 drivers.
  7. Removes from `order:ping:timeouts`.
  8. Sets `order:driver:lock` = "CANCELLED" (TTL 24h) → Prevents any in-flight accept.
  9. Sets `order:dispatch:lock` = "CANCELLED" (TTL 24h) → Prevents re-dispatch.
  10. All 10 riders' UIs show the ping disappearing simultaneously.

### 9.2 Restaurant Cancellation While in Delayed Queue
- [ ] **ORDER_CANCELLED_BY_RESTAURANT**: Order sitting in `delayed_dispatch_queue` between batches → Cancellation event → `TerminalStateStrategy` removes from queue, sets both locks to "CANCELLED" → When poller next picks it up, it reads "CANCELLED" and skips.

### 9.3 Admin Cancellation During Active Ping
- [ ] **ORDER_CANCELLED_BY_ADMIN**: Same flow as customer cancellation → All 10 pending pings cleaned → Order terminated.

### 9.4 Admin Force Assignment
- [ ] **forceAssignOrder During Active Ping**: Admin calls `/api/v1/internal/admin/delivery/orders/{orderId}/assign?driverId=X` → `forceAssignOrder()`:
  1. Reads and deletes `order:ping:pending` (all 10 drivers).
  2. Deletes `driver:pending_ping` for each of the 10.
  3. **NEW (Fixed)**: Calls `releaseDriverLock()` for the OTHER 9 drivers who were pinged, releasing them back to the available pool.
  4. Removes from `order:ping:timeouts`.
  5. Removes from `delayed_dispatch_queue`.
  6. Force-sets `order:driver:lock` = driverId (TTL 24h).
  7. Sets `driver:active_order` for the assigned driver.
  8. Sets `order:dispatch:lock` = "CANCELLED" → Prevents re-dispatch from stale events.
  9. DB: Assigns driver, sets status to ON_DELIVERY (or ONLINE→ON_DELIVERY if was OFFLINE).
  10. Emits `DRIVER_ASSIGNED` outbox event.
  11. Removes assigned driver from `drivers:available` pool.

- [ ] **forceAssignOrder to OFFLINE Driver**: Admin force-assigns to a driver whose status is OFFLINE → Code first sets status to ONLINE, then calls `acceptOrder()` to transition to ON_DELIVERY → Works correctly.
- [ ] **forceAssignOrder When No Ping Active**: No pending pings exist → `pendingDrivers` is null/empty → Force assign still works (just sets lock and emits event).
- [ ] **forceAssignOrder DB Failure**: Transaction fails → Redis lock deleted → Exception propagated to admin → No partial state.

### 9.5 DRIVER_ASSIGNED as Terminal Event
- [ ] **TerminalStateStrategy on DRIVER_ASSIGNED**: `DRIVER_ASSIGNED` is in `TerminalStateStrategy.getEventTypes()` → When processed:
  - Does NOT delete `order:dispatchPayload` (needed later for OTP validation).
  - Deletes `order:rejected_drivers` and `dispatch_failed_cycles`.
  - Removes from `delayed_dispatch_queue`.
  - Does NOT enter the cancellation cleanup block (not a cancellation event).
  - Sets `order:dispatch:lock` = "CANCELLED" → Prevents any stale re-dispatch events.

### 9.6 PRIORITY_DISPATCH_FAILED as Terminal Event
- [ ] **PRIORITY_DISPATCH_FAILED Cleanup**: Terminal strategy enters cancellation block → Cleans pending pings, sets driver lock to "CANCELLED", dispatch lock to "CANCELLED" → Downstream (CustomerApplication) sets order status to `REQUIRES_MANUAL_INTERVENTION`.

### 9.7 ORDER_REJECTED (Restaurant Rejects Order)
- [ ] **Restaurant Rejects**: `ORDER_REJECTED` → Terminal strategy cleans all dispatch state → Any active pings cancelled → Any queued retries removed.

### 9.8 ORDER_DELAY_REJECTED
- [ ] **Delay Extension Rejected**: If the system requested a delay extension and it was rejected → Terminal cleanup → Dispatch terminated.

---

## 10. Driver Abort (Post-Accept, Pre-Pickup)

### 10.1 Normal Abort
- [ ] **Driver Aborts After Accepting**: Driver accepted order, is en route to restaurant → Taps "Abort" → `OrderExecutionService.abortOrder()`:
  1. Verifies `order:driver:lock` matches the driver.
  2. DB: Sets driver status back to ONLINE, creates `ORDER_DRIVER_REJECTED` outbox event.
  3. Redis: Deletes `order:driver:lock`, deletes `driver:active_order`.
  4. Increments rejection count for this driver.
  5. Adds driver back to `drivers:available` pool.
  6. Calls `releaseDriverLock()` via MapsIntegration.
  7. `ORDER_DRIVER_REJECTED` → triggers new dispatch cycle → New batch of drivers found.

### 10.2 Abort Race Conditions
- [ ] **Abort With Optimistic Locking Failure**: Concurrent update to driver entity → Retry up to 3 times with jitter (100-200ms) → If all 3 fail, throw RuntimeException.
- [ ] **Abort After Order Cancelled**: Driver tries to abort but order was just cancelled → `order:driver:lock` no longer matches (set to "CANCELLED") → `IllegalArgumentException("Driver is not assigned to this order")`.

---

## 11. Stranded Driver Recovery

### 11.1 Cancellation After Driver Accepted
- [ ] **Order Cancelled Post-Assignment**: Driver accepted and is ON_DELIVERY → Customer or admin cancels → `TerminalStateStrategy`:
  1. Reads assigned driverId from `order:driver:lock` (or `order:driver:stranded`).
  2. Sets `order:driver:stranded:{orderId}` = driverId.
  3. Releases driver lock via MapsIntegration.
  4. DB: Resets driver status from ON_DELIVERY → ONLINE.
  5. Adds driver back to `drivers:available` pool.
  6. Deletes `order:driver:stranded` entry.

### 11.2 Edge Cases
- [ ] **Stranded Key Already Exists**: Order was previously cancelled, stranded key exists → `TerminalStateStrategy` prefers the stranded driver over the lock driver → Correct driver is released.
- [ ] **Lock Value is "CANCELLED" or "locked"**: `driverId.equals("CANCELLED")` or `driverId.equals("locked")` → Skips driver release (no actual driver to release).
- [ ] **Driver Already ONLINE or OFFLINE**: Terminal strategy checks `executive.getStatus() == ON_DELIVERY` before resetting → If already ONLINE/OFFLINE, no DB update → Prevents invalid state transitions.

---

## 12. Poller & Scheduler Coordination

### 12.1 DelayedDispatchPoller
- [ ] **Poller Processes Multiple Orders**: 3 orders in `delayed_dispatch_queue` with expired scores → Poller processes all 3 in a single cycle → Each gets its own `dispatch_processing_lock`.
- [ ] **Poller Exception Handling**: One order fails processing → Exception caught → Other orders in the batch still processed → Failed order remains in queue for next cycle.
- [ ] **Poller Removes Entry After Dispatch**: After successful `dispatchNearestDriver()` call → Removes orderId from `delayed_dispatch_queue` → Won't be re-processed.

### 12.2 DriverPingTimeoutPoller
- [ ] **Processes Multiple Timeouts**: 5 orders have expired pings → All 5 processed in one cycle → Each calls `timeoutOrderPing()` independently.
- [ ] **Timeout Processing Failure**: One `timeoutOrderPing()` throws → Exception caught → Other orders still processed.

### 12.3 StaleDriverSweeperDaemon
- [ ] **Driver Stops Sending Location**: Driver's WebSocket disconnects → No more location updates → `driver_last_ping` ZSET entry becomes stale (> 60s) → Sweeper finds it → Sets driver status to OFFLINE in DB → Removes from `drivers:geo`, `driver_last_ping`, `drivers:available` → Driver can no longer be dispatched to.
- [ ] **Sweep During Active Delivery**: Driver is ON_DELIVERY and stops sending updates → Sweeper sets to OFFLINE → **Potential Issue**: Driver who is mid-delivery gets marked OFFLINE — but since they're ON_DELIVERY (not in available pool), they won't be dispatched to anyway. However, their geo entry is removed, which may affect live tracking.
- [ ] **Orphaned Redis Entry (No DB Record)**: DriverId in `driver_last_ping` but not in database → Sweeper detects and cleans up → No error.
- [ ] **Batch Processing**: Multiple stale drivers found → All fetched from DB in single `findAllById` call → All updated in single `saveAll` call → Redis cleanup only happens AFTER DB commit.
- [ ] **DB Failure During Sweep**: `saveAll` throws → No Redis cleanup → Drivers remain in Redis → Retried next cycle (dual-write prevention).

### 12.4 RedisLockReaperTask
- [ ] **Orphaned Active Order Lock**: Driver is ONLINE in DB but has `driver:active_order` pointing to an order → Lock reaper detects mismatch → Deletes `driver:active_order` and `order:driver:lock` → Prevents stale locks from blocking future dispatches.
- [ ] **Orphaned Order Driver Lock**: `order:driver:lock:{orderId}` points to a driverId whose DB status is ONLINE/OFFLINE (not ON_DELIVERY) → Lock reaper deletes the lock → Order can be re-dispatched or is already terminal.
- [ ] **Uses SCAN Instead of KEYS**: Scans `driver:active_order:*` and `order:driver:lock:*` with SCAN (non-blocking) → Won't freeze Redis under load.
- [ ] **Concurrent Reaper Instances**: Acquires `lock:reaper_task_execution` (1 min TTL) → Only one instance runs → Lock released in `finally` block.

---

## 13. Large-Scale Batching Flow (1000+ Drivers)

### 13.1 Multi-Batch Iteration
- [ ] **Batch 1 → Batch N**: 1000 drivers online. Batch 1 (nearest 10) pinged → All 10 reject → Each has `rejectionCount = 1` → Re-dispatch → Same 10 pinged again (count < 5) → All reject again → `rejectionCount = 2` → Repeats until all 10 have 5 rejections → All 10 added to `excludedDriverIds` → Batch 2 (next nearest 10) dispatched → Process repeats.
- [ ] **Total Iterations for 1000 Drivers**: 100 batches × 5 rejection cycles each = 500 dispatch attempts before global exhaustion.
- [ ] **Performance Under Load**: Each dispatch cycle involves: Kafka publish, MapsIntegration Kafka consume, Redis geo query, Ola Maps API (or fallback), Kafka publish back, Delivery service Kafka consume, 10 WebSocket/push messages → System must handle hundreds of cycles for a single difficult order.

### 13.2 Partial Batch Acceptance
- [ ] **Driver in Batch 3 Accepts**: Batches 1 and 2 fully rejected (20 drivers excluded) → Batch 3 dispatched → Driver 25 accepts → `DRIVER_ASSIGNED` → All remaining pings in Batch 3 cleared → `order:dispatch:lock` set to "CANCELLED" → No Batch 4.

---

## 14. Kafka Reliability & Retry Semantics

### 14.1 Kafka Consumer Retries
- [ ] **OrderEventConsumer Retry**: Event processing fails → `@RetryableTopic` retries 4 times with backoff (2s, 4s, 8s, capped at 10s) → If all fail → Message sent to DLT (Dead Letter Topic) → `handleDltMessage` logs error.
- [ ] **Idempotent Event Processing**: Kafka may deliver the same `ORDER_ACCEPTED` or `ORDER_DRIVER_REJECTED` twice → Guards (`setIfAbsent` on dispatch lock, dispatch lock "CANCELLED" check) ensure idempotent handling.

### 14.2 Kafka Producer Reliability
- [ ] **Dispatch Request Publish Failure**: `kafkaTemplate.send().get(3, TimeUnit.SECONDS)` with synchronous wait → If publish fails, `RuntimeException` thrown → Caller (poller or strategy) catches it → Order remains in queue for retry.
- [ ] **DISPATCH_CANDIDATE_FOUND Publish Failure**: MapsIntegration fails to publish → Exception propagated → Kafka consumer retry logic re-processes the dispatch request.

### 14.3 Dead Letter Topic
- [ ] **Poison Pill Message**: Malformed Kafka message that can never be processed → After 4 retries → Lands in DLT → Logged → Requires manual investigation.

---

## 15. State Machine & Status Transitions

### 15.1 DeliveryExecutive Status Transitions
| From | To | Trigger |
|---|---|---|
| ONLINE | ON_DELIVERY | `acceptOrder()` — Driver accepts order |
| ON_DELIVERY | ONLINE | `completeDelivery()` — Delivery completed, or abort/cancellation |
| ONLINE | OFFLINE | `goOffline()` — Driver goes offline, or StaleDriverSweeper |
| OFFLINE | ONLINE | `goOnline()` — Driver starts shift |

- [ ] **Illegal Transition**: Driver is OFFLINE → Order pinged (shouldn't happen, but if it does) → Driver taps Accept → `acceptOrder()` from OFFLINE state → `IllegalStateTransitionException` → Accept fails → Order continues to other drivers.
- [ ] **Force Assign OFFLINE Driver**: Admin force-assigns to OFFLINE driver → Code explicitly sets ONLINE first → Then calls `acceptOrder()` → Transition is OFFLINE → ONLINE → ON_DELIVERY.

### 15.2 Order Status Sync (Restaurant Status)
- [ ] **Forward-Only Transitions**: `OrderStatusUpdatedStrategy` enforces `newStatus.getSequence() > currentStatus.getSequence()` → Backward transitions ignored → Prevents out-of-order Kafka messages from corrupting status.
- [ ] **Pub/Sub for Live Updates**: Status changes published to `restaurant-status:order:{orderId}` channel → Rider app can subscribe for live updates.

---

## 16. Infrastructure Failure Edge Cases

### 16.1 Redis Connection Failure
- [ ] **Redis Down During Poller**: `DelayedDispatchPoller` fails to acquire lock or read queue → Exception caught → Logged → Poller retries on next 5s cycle → Orders remain safely in the ZSET (durable).
- [ ] **Redis Down During Accept**: `acceptOrderPing()` → Lua script fails → `RuntimeException` → Driver sees error → Order remains in pending state → Timeout will eventually clean up.
- [ ] **Redis Recovers**: After Redis comes back → All ZSETs, SETs, and keys are intact (assuming no data loss) → Pollers resume normally → Orders that were stuck get processed.

### 16.2 Database Connection Failure
- [ ] **DB Down During Accept**: Lua script succeeds (Redis lock acquired) → DB transaction fails → Catch block rolls back Redis lock → Other drivers can attempt → System self-heals.
- [ ] **DB Down During Terminal Cleanup**: Driver release fails → Stranded driver key remains → Lock reaper will clean up on next cycle → Self-healing within 2 minutes.

### 16.3 Kafka Connection Failure
- [ ] **Kafka Down (Producer Side)**: `dispatchNearestDriver()` fails to publish → `RuntimeException` → Order remains wherever it was → If in poller queue, stays in queue → Retried next cycle.
- [ ] **Kafka Down (Consumer Side)**: Events pile up in Kafka topics → When consumer recovers, processes backlog → Guards prevent duplicate processing → Eventually consistent.

### 16.4 Service Restart
- [ ] **DeliveryExecutiveApplication Restarts**: All in-memory state (WebSocket sessions) lost → `activeSessions` and `userSessions` maps empty → Drivers must reconnect WebSocket → Pings sent to disconnected drivers will fail (logged) → Timeout will handle gracefully.
- [ ] **Pending Orders in Redis Survive Restart**: All dispatch state is in Redis (not in-memory) → Pollers resume scanning after restart → No data loss.

### 16.5 MapsIntegration Service Down
- [ ] **releaseDriverLock Fails**: MapsIntegration service is down → `releaseDriverLock()` throws → Error logged but NOT re-thrown → Order processing continues → Driver may remain unavailable temporarily → StaleDriverSweeper or availability poller will eventually reconcile.

---

## 17. Memory Leak & Data Consistency

### 17.1 TTL-Based Cleanup
- [ ] **All Dispatch Keys Have TTLs**:
  - `order:dispatch:lock` — 24h
  - `order:dispatchPayload` — 24h
  - `order:driver:lock` — 24h (set by Lua script)
  - `driver:active_order` — 24h
  - `order:dispatch_failed_cycles` — 2h
  - `order:rejected_drivers` — 2h
  - `order:ping:pending` — 60s
  - `driver:pending_ping` — 60s
  - `order:driver:stranded` — 24h
  - Processing locks — 30s
  - Global poller locks — 4s
  - Reaper lock — 1 min
  - ETA cache — 30s

### 17.2 Cleanup on Terminal Events
- [ ] **Full Cleanup on Cancellation**: Every cancellation path (`ORDER_CANCELLED`, `ORDER_CANCELLED_BY_CUSTOMER`, `ORDER_CANCELLED_BY_RESTAURANT`, `ORDER_CANCELLED_BY_ADMIN`) deletes: dispatch payload, rejected drivers hash, failed cycles counter, pending pings, ping timeouts, delayed queue entry, and sets both locks to "CANCELLED".
- [ ] **Partial Cleanup on DRIVER_ASSIGNED**: Keeps dispatch payload (needed for OTP), deletes rejected drivers and failed cycles, removes from delayed queue, sets dispatch lock to "CANCELLED".
- [ ] **Partial Cleanup on DISPATCH_FAILED**: Only increments failed cycles, re-queues → Does NOT clean up payload or dispatch lock → These are needed for retries.

### 17.3 Driver Pool Consistency
- [ ] **Pool Drain on Dispatch**: 10 drivers removed from `drivers:available` on dispatch → Must be added back on reject/timeout/cancellation via `releaseDriverLock()`.
- [ ] **Pool Restoration Failure**: If `releaseDriverLock()` fails (MapsIntegration down) → Error logged, NOT re-thrown → Driver stays out of pool temporarily → StaleDriverSweeper handles eventual reconciliation by removing inactive drivers entirely.

---

## 18. UI & SSE Synchronization

### 18.1 Rider UI
- [ ] **Ping Appears on Rider App**: WebSocket message `{"type": "NEW_ORDER_DISPATCH", "orderId": "..."}` → Rider sees Accept/Reject buttons.
- [ ] **Ping Disappears on Cancellation**: Order cancelled → Ping keys deleted → Rider's `getPendingPing()` returns null → UI hides the ping.
- [x] **UI Re-dispatch Race Condition (Fixed)**: PREVIOUSLY: Rider rejecting/timing out added the order to `rejectedIds`. When re-dispatched, the UI filtered out the new ping because it was still in `rejectedIds`, hiding it from the driver. NOW: `DeliveryDashboard.tsx` explicitly removes the order from `rejectedIds` when a new ping is received.
- [ ] **Accept Confirmation**: Driver taps Accept → API call succeeds → Driver transitions to delivery view.
- [ ] **Accept Failure UX**: API returns 409/500 → Driver sees appropriate error message ("Order already taken" / "Order cancelled").

### 18.2 Customer & Restaurant UI
- [ ] **PRIORITY_DISPATCH_FAILED → REQUIRES_MANUAL_INTERVENTION**: CustomerApplication updates order status → SSE `OrderStatusSyncEvent` fired → Customer sees "Finding driver..." change to status indicating manual review.
- [ ] **Admin Dashboard**: Order appears in "Manual Interventions" tab for admin to force-assign.

### 18.3 Restaurant Status Updates
- [ ] **Restaurant Status Pub/Sub**: `OrderStatusUpdatedStrategy` publishes status changes to `restaurant-status:order:{orderId}` channel → Rider app subscribes → Shows real-time restaurant preparation status.

---

## 19. Outbox Pattern Reliability

### 19.1 Outbox Event Creation
- [ ] **All Dispatch Events Use Outbox**: `DRIVER_ASSIGNED` and `ORDER_DRIVER_REJECTED` events are created via `outboxEventHelper.createOutboxEvent()` → Saved in the same DB transaction as the entity update → Guarantees at-least-once delivery even if Kafka is temporarily down.

### 19.2 Outbox Failure Scenarios
- [ ] **DB Down During Outbox Save**: Both entity update and outbox event fail atomically → No partial state → Transaction rolled back → Can be retried.
- [ ] **Outbox Poller Picks Up Event**: Outbox poller reads unsent events → Publishes to Kafka → Marks as sent → Even if the application crashes between creating the event and Kafka publish, the outbox poller ensures delivery.

---

## 20. Complete End-to-End Flow (Happy Path Timeline)

```
T+0s     ORDER_ACCEPTED → dispatch:lock acquired → payload cached → Kafka: LOGISTICS_DISPATCH_REQUEST
T+0.5s   MapsIntegration: geo query + ETA sort → top 10 candidates → removed from available pool
T+1s     Kafka: DISPATCH_CANDIDATE_FOUND → ping:pending SET created → 10 drivers pinged
T+5s     Driver 3 taps Accept → Lua: lock acquired → DB: DRIVER_ASSIGNED outbox event → status ON_DELIVERY
T+5.1s   Remaining 9 drivers' pending pings cleared → ping:timeouts removed
T+5.5s   DRIVER_ASSIGNED via Kafka → TerminalStateStrategy: dispatch:lock = CANCELLED → cleanup
T+5.5s   CustomerApplication: order status updated → SSE to customer UI
```

## 21. Complete End-to-End Flow (Worst Case Timeline — All Drivers Reject)

```
T+0s      ORDER_ACCEPTED → dispatch → Batch 1 (10 drivers pinged)
T+60s     All 10 timeout → ORDER_DRIVER_REJECTED → queued in delayed_dispatch_queue (score: T+70s)
T+75s     Poller picks up → re-dispatch → same 10 drivers (rejection count: 1) → Batch 1, Cycle 2
T+135s    All 10 timeout → ORDER_DRIVER_REJECTED → re-queued (score: T+145s)
...       (repeat until rejection count = 5 for all 10)
T+~25min  All 10 have count=5 → excluded → Batch 2 dispatched (next 10 nearest)
...       (repeat for 100 batches × 5 cycles)
T+~42hr   All 1000 drivers excluded → DISPATCH_FAILED × 5 → PRIORITY_DISPATCH_FAILED
          → REQUIRES_MANUAL_INTERVENTION
```
