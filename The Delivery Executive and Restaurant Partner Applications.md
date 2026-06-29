# **The Delivery Executive and Restaurant Partner Applications: Logistics, Telemetry, and Integrated Systems Architecture**

## **1\. Architectural Ecosystem Overview**

The modern food delivery ecosystem represents a highly complex, event-driven network that synchronizes asynchronous supply from restaurant partners with dynamic logistics executed by delivery executives, all driven by fluctuating consumer demand. Achieving sub-second dispatch, accurate turn-by-turn routing, and seamless financial settlements requires a deeply integrated architectural framework1.  
The ensuing architectural analysis details the comprehensive systems design required to operate this ecosystem. The architecture centers on a backend powered by Java 17 and Java 21 utilizing the Spring Boot 3.x framework2. It leverages PostgreSQL equipped with the PostGIS extension for managing structural geospatial data, and Redis Geospatial structures for handling high-frequency telemetry2. Apache Kafka serves as the central nervous system for asynchronous event propagation across decoupled microservices1. Furthermore, this report details the functional architecture of the Delivery Executive application—focusing on background telemetry and logistics routing—as well as the optimized, automated onboarding workflow for the Restaurant Partner application. This workflow utilizes third-party Know Your Business (KYB) and verification APIs to guarantee compliance while providing an entirely frictionless user experience3.

## **2\. Client Application Framework Selection**

To construct a unified suite of applications (Customer, Delivery Executive, and Restaurant Partner) that operate seamlessly across Web, Android, and iOS devices, the selection of the cross-platform User Interface (UI) framework is a foundational architectural decision. The evaluation matrix considers rendering performance, geospatial mapping capabilities, ecosystem maturity, and deployment efficiency.

### **2.1 Comparative Analysis of Cross-Platform Frameworks**

The modern landscape of cross-platform development is dominated by several key paradigms: JavaScript bridge-based frameworks, native-compilation toolkits, and declarative UI shared logic systems4. Each framework presents distinct trade-offs regarding how it interfaces with the underlying host operating system.  
React Native relies on mapping JavaScript components to native platform widgets via an asynchronous bridge5. While offering a massive web-developer ecosystem and Over-The-Air (OTA) update capabilities via technologies like CodePush, the JavaScript bridge introduces unavoidable communication overhead5. In applications requiring high-frequency geolocation updates and consistent 60fps map animations—critical requirements for a Delivery Executive application—this overhead frequently manifests as UI jank, latency, and memory bloat5. Complex lists and animations often suffer because the UI components map to different native widgets on each platform, complicating custom styling5.  
Kotlin Multiplatform (KMP) allows the sharing of pure business logic across Android and iOS while maintaining fully native UIs, or utilizing Compose Multiplatform for shared interfaces6. KMP produces excellent performance and type safety. However, it remains heavily skewed toward native mobile developers and often requires more platform-specific intervention than true single-codebase solutions6.  
Frameworks such as .NET MAUI and Avalonia UI are strong contenders within the Microsoft ecosystem, utilizing C\# and XAML4. Avalonia UI utilizes the SkiaSharp rendering engine to draw its own pixels, ensuring cross-platform consistency across desktop, mobile, and embedded environments4. However, the ecosystem and community support for complex mobile mapping solutions within .NET MAUI or Avalonia UI lag significantly behind mainstream alternatives, making it a suboptimal choice for a logistics-heavy application8.  
Flutter, developed by Google, bypasses native OEM widgets entirely. It renders every pixel directly onto the screen via the Skia (and newer Impeller) graphics engine5. The framework compiles Ahead-Of-Time (AOT) to native ARM machine code for iOS and Android, and utilizes WebAssembly (Wasm) for the Web7.

### **2.2 Framework Justification: Flutter and MapLibre Integration**

For this specific logistics and delivery ecosystem, Flutter represents the optimal choice for the Web, Android, and iOS applications7. The framework's ability to maintain a consistent 60 to 120 frames per second is vital for rendering complex UI overlays, live polyline routing on maps, and real-time telemetry dashboards without stutter5.  
A critical factor in this selection is Flutter's geospatial integration capabilities. Historically, rendering complex vector maps in cross-platform frameworks relied on platform channels that serialized data back and forth, leading to severe performance bottlenecks9. The modern flutter\_maplibre (MapLibre GL) package resolves this by providing direct native interoperability via Foreign Function Interfaces (FFI) and Java Native Interface (JNI) to MapLibre's native C++ SDKs9. This architecture avoids the performance bottlenecks associated with platform channels, allowing the Delivery Executive app to render thousands of vector tiles, dynamic routes, and real-time markers without blocking the main application thread9.  
Furthermore, Flutter’s reactive state management paradigms excel at isolating widget rebuilds. As background WebSockets stream continuous location updates, a well-architected Flutter application ensures that only the specific map marker or Estimated Time of Arrival (ETA) text node rebuilds, keeping memory usage idle at approximately 14-28MB10. This memory efficiency is paramount for low-end Android devices typically utilized by delivery fleets10.

| Evaluation Criteria | React Native | Kotlin Multiplatform | Flutter | .NET MAUI |
| :---- | :---- | :---- | :---- | :---- |
| **Language** | JavaScript / TypeScript | Kotlin | Dart | C\# |
| **UI Rendering Model** | Native widget mapping | Native UI / Compose | Direct Canvas (Skia/Impeller) | Handler Architecture |
| **Performance Profile** | Moderate (Bridge latency) | Native (AOT) | Near-Native (AOT Compiled) | Native (AOT/JIT mixed) |
| **Mapping Capability** | High overhead via bridge | Native SDKs | High performance via FFI | Platform channel overhead |
| **Target Platforms** | iOS, Android, Web | iOS, Android, Desktop | iOS, Android, Web, Desktop | iOS, Android, Windows, Mac |

## **3\. The Delivery Executive Application: Logistics and Telemetry**

The Delivery Executive application is the most technically demanding client within the food delivery ecosystem. It must operate reliably in highly volatile network conditions, rely on continuous background processing, and interact seamlessly with operating system hardware sensors2.

### **3.1 Detailed Use Case Diagrams and Workflows**

To capture the exhaustive requirements of the Delivery Executive application, the use cases are structured sequentially, representing the lifecycle of a delivery shift. The functional diagrams are represented here through comprehensive tabular state machines and narrative flows, detailing actors, preconditions, triggers, and alternate resolutions.

#### **Use Case 1: Shift Initiation and Availability Toggle**

The fundamental entry point for logistics tracking occurs when a driver initiates a shift. The system must establish a persistent connection and verify hardware permissions.

| Attribute | Specification Details |
| :---- | :---- |
| **Primary Actor** | Delivery Executive (DE) |
| **System Components** | Flutter Client, OS Location Services, MapsIntegration Backend |
| **Pre-conditions** | DE is authenticated via JWT; GPS hardware is active; Battery optimization disabled. |
| **Trigger** | DE toggles the UI switch to "Online". |
| **Main Success Flow** | 1\. App requests high-accuracy background location permissions. 2\. App initiates a WebSocket connection to ws://\[server\]/tracking. 3\. App sends a REST POST to /api/fleet/availability with payload { "cityId": "BENGALURU", "driverId": "uuid", "available": true }. 4\. Backend marks the DE as available in PostgreSQL and adds them to the Redis Spatial Index. |
| **Alternate Flow** | If the OS denies background location permissions, the app gracefully blocks the "Online" transition and surfaces a mandatory OS settings redirect2. |
| **Post-conditions** | Driver is visible to the algorithmic dispatch engine. |

#### **Use Case 2: Algorithmic Order Dispatch and Atomic Locking**

Once an order is paid and accepted by the kitchen, the system must locate the most optimal driver and ensure they are exclusively locked to that order to prevent race conditions.

| Attribute | Specification Details |
| :---- | :---- |
| **Primary Actor** | Logistics Dispatch Service (System), Delivery Executive |
| **System Components** | Redis Geospatial, Ola Maps Distance Matrix API, Firebase Cloud Messaging |
| **Pre-conditions** | DE is Online, unassigned, and located within a 5.0 km radius of the restaurant2. |
| **Trigger** | Order Service publishes a dispatch event to Kafka. |
| **Main Success Flow** | 1\. MapsIntegration service queries Redis opsForGeo().radius(...) for nearby drivers. 2\. Service calls Ola Maps Distance Matrix to calculate true road ETAs. 3\. System pushes an FCM data payload to the optimal DE. 4\. DE UI awakens, displaying pickup location, ETA, and earning potential. 5\. DE accepts the order via the UI. 6\. Backend atomically locks the driver using Redis setIfAbsent("driver:lock:\<driverId\>"). |
| **Alternate Flow** | If multiple drivers are pinged due to high demand, the Redis distributed lock ensures only the first acceptance payload is processed, returning a 409 Conflict to subsequent drivers to prevent double-assignment2. |

#### **Use Case 3: Turn-by-Turn Navigation and Telemetry Streaming**

Upon assignment, the application must guide the driver to the restaurant and continuously broadcast their location to allow the customer and restaurant to track progress.

| Attribute | Specification Details |
| :---- | :---- |
| **Primary Actor** | Delivery Executive, MapLibre Engine, Customer Application |
| **System Components** | Flutter MapLibre FFI, OS Background Services, WebSockets |
| **Pre-conditions** | Order is exclusively assigned to the DE. |
| **Trigger** | UI transitions to the active order view. |
| **Main Success Flow** | 1\. App executes GET /api/logistics/route providing origin and destination. 2\. Backend proxies Ola Maps Directions API, returning polyline coordinates. 3\. MapLibre SDK natively renders the route via a GeoJSON layer over vector tiles. 4\. Background service polls GPS every 3-5 seconds, transmitting JSON telemetry payloads over the active WebSocket. 5\. Backend aggregates telemetries and broadcasts them to the customer app. |
| **Alternate Flow** | If the network drops, the app caches GPS points in a local SQLite buffer. Upon reconnection, the buffer is flushed to the server to maintain payout accuracy based on distance traveled2. |

### **3.2 Functional Architecture: Real-Time Telemetry and WebSocket Management**

The core of the telemetry system is the persistent, bi-directional WebSocket connection. Utilizing standard HTTP polling is entirely insufficient for logistics applications due to excessive header overhead, battery drain, and unacceptable latency14. However, maintaining WebSockets on mobile operating systems presents severe architectural challenges that the Flutter application must address.

#### **3.2.1 Connection Lifecycle, Heartbeats, and State Synchronization**

Mobile networks are inherently unstable, transitioning frequently between cellular towers and Wi-Fi networks. A naive WebSocket implementation treats connections as reliable channels, which results in "zombie connections" where the client believes it is connected, but the server's TCP socket has timed out or been dropped13.  
To build a production-ready telemetry client, the application must implement a strict heartbeat mechanism. The client must transmit periodic ping frames to the server. If the server does not receive a ping within a defined temporal threshold, it terminates the session to prevent memory leaks13. Conversely, if the client misses an expected server pong, it definitively assumes the connection is dead and initiates a reconnection sequence13.  
When a disconnection is detected, the application must not instantly hammer the server to reconnect. Simultaneous reconnections from a large fleet cause a "thundering herd" scenario that overwhelms load balancers and crashes backend infrastructure14. To mitigate this, the Flutter client implements an exponential backoff algorithm (e.g., waiting 1s, 2s, 4s, 8s, capping at a maximum of 30s) and introduces randomized jitter to distribute the reconnection load evenly across the server cluster10.

#### **3.2.2 Background Suspension and Offline Buffering**

Operating systems enforce aggressive battery management policies. When a Flutter application is backgrounded on iOS, the operating system aggressively suspends network threads and restricts background execution14. The architecture must utilize foreground services on Android and background task requests on iOS to keep the GPS hardware active.  
If the WebSocket is forcibly severed by the OS or a network dead zone, the application cannot simply discard telemetry data. Real-time applications require assurance that critical messages reach their destination. Without proper message handling, applications suffer from lost messages and inconsistent states13. The Flutter application resolves this by buffering location payloads in a local SQLite database or an in-memory queue. When the network connection is re-established, the application flushes the queued array to the server. This ensures the backend receives a complete, chronological path of the driver's movements, which is absolutely critical for calculating accurate distance-based payouts and resolving route disputes10.

### **3.3 Backend Telemetry Ingestion (MapsIntegration Service)**

The backend ingestion of this massive volume of telemetry is handled by the MapsIntegration Spring Boot service. Writing thousands of GPS pings directly to a relational database like PostgreSQL per second would instantly cause I/O bottlenecks and transaction log exhaustion.  
Instead, the service utilizes a TrackingWebSocketHandler implementing Project Reactor (specifically Flux and Sinks.Many) to manage reactive backpressure2. The stream buffers incoming events using .bufferTimeout(1000, 500ms) and flushes them to Redis in aggregate batches using .publishOn(Schedulers.boundedElastic()) to avoid blocking the event loop2. The service also implements graceful degradation via .onBackpressureDrop(), configured to discard excess events if the system becomes overloaded, thereby preventing Out-Of-Memory (OOM) crashes during severe traffic spikes2.  
The geospatial tracking operates entirely in-memory. Driver coordinates are updated in Redis using the command opsForGeo().add("drivers:geo:\<cityId\>", Point(lng, lat), driverId)2. This architecture enables sub-millisecond execution of radius queries (opsForGeo().radius(...)) when the dispatch algorithm searches for nearby candidates2.

## **4\. Maps and Geolocation API Ecosystem**

Visualizing the ecosystem and calculating accurate logistics requires a robust external mapping provider. While Google Maps is the global incumbent, recent pricing adjustments and the need for localized accuracy make alternative providers highly attractive. **Ola Maps** (by Krutrim) is the optimal provider for Indian operations, offering a generous tier of 5 million free API calls per month for developers, and charging approximately 50% less than Google's reduced rates for enterprise volumes15.

### **4.1 Ola Maps Integration Strategy**

The platform relies on several specific Ola Maps endpoints to power the logistics engine and the user interfaces17.

* **Places Autocomplete API:** Utilized in both the Customer and Restaurant applications for predictive text search when setting delivery addresses. By providing fast, predictive suggestions as the user types, the platform reduces cart abandonment associated with address entry friction17.  
* **Reverse Geocoding API:** Converts a Delivery Executive's or Customer's raw GPS coordinates into highly accurate, human-readable text (e.g., street name, neighborhood)2. This is critical for users who rely on the "Locate Me" feature rather than manually typing their address.  
* **Distance Matrix API (Basic & Advanced):** This API is the mathematical core of the dispatch engine. It computes the true travel distance and routing ETA between the restaurant (the origin) and multiple nearby drivers (the destinations)18. Because it calculates road distance rather than straight-line distance, it prevents the system from assigning a driver who is physically close but separated by an uncrossable highway or river.  
  * *Architectural Imperative:* When the MapsIntegration service constructs URIs for the Distance Matrix API using Spring's UriComponentsBuilder, developers must exercise caution. The pipe character (|) used by Ola Maps to separate multiple origin coordinates can be double-encoded to %257C if relying on automatic URI variable encoding. This results in the Ola Maps API returning a 404 Not Found. The system resolves this by constructing the URI path manually using string concatenation and invoking URI.create()2.  
* **Directions API:** Provides the specific turn-by-turn polyline coordinates and navigation instructions. This data is fed directly into the MapLibre GL SDK within the Flutter application to render the visual route overlay2.

### **4.2 Resilient Dispatch and Circuit Breaking**

Because the entire dispatch system relies on the Ola Maps Distance Matrix API, the architecture must account for the possibility of external API outages or extreme latency. The LogisticsDispatchService wraps all outbound routing calls in a Resilience4j @CircuitBreaker (e.g., @CircuitBreaker(name \= "olaMapsRouting", fallbackMethod \= "..."))2.  
If the Ola Maps routing engine experiences downtime, the circuit breaker opens. Instead of throwing a 500 Internal Server Error and halting all order assignments, the system falls back to calculating a mathematical Haversine (straight-line) distance based on the coordinates stored in Redis2. While slightly less accurate than road routing, this graceful degradation ensures that the business can continue operating uninterrupted. Furthermore, Ola Maps API responses are cached in Redis using a 30-second Time-To-Live (TTL). The cache key is deterministically generated based on candidate origins, preventing redundant API calls when multiple customers track drivers in identical geographic bounds2.

## **5\. The Restaurant Partner Application: Onboarding and KYB Experience**

For food delivery platforms, the initial onboarding of Restaurant Partners dictates the long-term success of the supply side. A process with high friction, requiring physical documents or extensive manual data entry, results in severe abandonment rates3. Conversely, a lax onboarding process invites fraudulent vendors, leading to poor customer experiences and severe regulatory penalties. The optimal architectural approach leverages third-party APIs to automate data extraction, executing Know Your Business (KYB) and Know Your Customer (KYC) compliance invisibly in the background3.

### **5.1 Optimal User Experience Flow for Registration**

To deliver the best possible user experience, the Restaurant Partner Flutter application minimizes manual data entry. The UI is structured as a phased, wizard-like flow that relies heavily on Optical Character Recognition (OCR) and instant API pre-validation.

#### **Step 1: Authentication and Basic Identity**

The process begins with the merchant entering their mobile number. The system verifies this via a One-Time Password (OTP). This OTP is routed through the centralized Notification Service, which utilizes SMS aggregators like Fast2SMS or MetaReach. These aggregators are chosen over direct telecom connections (like Jio or Airtel) because they provide network failover redundancy, routing the OTP through alternative cellular networks if the primary provider is congested, ensuring delivery in under 5 seconds1. The user then selects their legal entity type (e.g., Proprietorship, LLP, Private Limited).

#### **Step 2: Automated KYB and Tax Verification (GSTIN & PAN)**

To verify the business's legality without requiring manual form completion, the application prompts the user to upload a photo of their Goods and Services Tax (GST) Certificate. The app processes the image using edge-based OCR or forwards it to an API suite such as **Signzy**, **Karza Technologies**, or **OnGrid**3.  
The backend extracts the GSTIN and executes a Fetch GSTIN Detailed API call. This API interfaces directly with government MCA and Tax portals in real-time. It retrieves the registered business name, the exact registered address, and the associated Permanent Account Number (PAN)3. The Flutter UI instantly auto-fills the entire registration form. The user merely reviews the data and clicks "Confirm." This process drastically reduces cognitive load, eliminates typing errors, and provides the platform with cryptographically verified business intelligence3.

#### **Step 3: Sector-Specific Compliance (FSSAI)**

In India, all food business operators require a Food Safety and Standards Authority of India (FSSAI) license. The user inputs their 14-digit FSSAI number into the application. The backend executes an FSSAI verification API call (typically costing between ₹1.49 and ₹2.49 per verification)22. The system instantly verifies the license validity period, ensures it is active, and cross-references the registered premise address against the location provided in Step 2 to detect potential fraudulent ghost kitchens3.

#### **Step 4: Financial Settlement Verification (Penny Drop)**

The most critical point of failure in vendor relationships is delayed or failed payouts due to incorrectly typed bank account details. To prevent this, the system mandates bank account verification before the vendor can accept their first order23.  
The user enters their Account Number and IFSC code. The backend immediately initiates a "Penny Drop" via the **Cashfree Payouts API** or the **RazorpayX Bank Account Verification API**23. The system executes an IMPS transfer, depositing exactly ₹1.00 into the provided account. Within 3 seconds, the API returns a success status, the verified name registered at the bank, and a phonetic name match score25. If the name returned by the bank matches the business PAN or GSTIN registration, the bank account is permanently linked and cryptographically locked in the PostgreSQL database. This verification entirely prevents the tedious 2-5 day refund cycle associated with wrong-account disbursals, ensuring the vendor receives their payouts flawlessly on a T+1 schedule23.

| Verification Type | Market Providers | Core Functionality | Average Latency | Approximate Cost |
| :---- | :---- | :---- | :---- | :---- |
| **GSTIN & CIN** | Signzy, Karza, OnGrid | Validates entity legality, auto-fills address and PAN. | \~2 \- 4 seconds | ₹2.49 \- ₹3.49 per call |
| **FSSAI License** | Signzy, Karza | Verifies food safety compliance and active status. | \~2 \- 3 seconds | ₹1.49 \- ₹2.49 per call |
| **Bank Account** | Cashfree, RazorpayX | "Penny drop" IMPS test; confirms beneficiary name. | \< 3 seconds | ₹2.50 \- ₹3.00 per test |

## **6\. Core Backend Microservices and Ecosystem Integration**

The underlying architecture relies on a suite of pre-implemented Java Spring Boot microservices. Integrating the new client applications requires strict adherence to the established communication protocols, data constraints, and event-driven architectures.

### **6.1 Database Architecture and Immutability Standards**

The underlying PostgreSQL database schemas enforce absolute data integrity, particularly for financial ledgers and geospatial routing.  
The MapsIntegration service relies heavily on the PostGIS extension. Location columns within the restaurants and customers tables are strictly defined as GEOMETRY(Point, 4326\) to natively represent WGS 84 coordinates2. Furthermore, these columns utilize GiST (Generalized Search Tree) indexes to enable high-speed spatial bounding box queries. Backend engineers are explicitly instructed not to alter these geometries to standard text; all queries must utilize native PostGIS functions2.  
Within the Payment Service, all financial records (e.g., Transaction, PaymentIntent, Refund) are strictly immutable1. Modifying an active order's financial state relies on double-entry ledger principles and optimistic locking to handle high-throughput concurrent order adjustments without creating database bottlenecks1. Check constraints at the database level ensure that refunds processed cannot mathematically exceed the original captured amounts2. Financial amounts are strictly stored as DECIMAL(15,2) or in integer subunits (paise) to prevent floating-point arithmetic errors2.

### **6.2 Payment Infrastructure Integration**

The Payment Service orchestrates the creation of checkout intents and securely processes asynchronous webhooks. While standard collections can be handled by Razorpay or Cashfree at a standard \~1.95% transaction fee, zero-commission architectures utilize specialized flat-fee routing23.  
To achieve 0% commission on UPI transfers, the system integrates with a flat-fee infrastructure provider like **VyaparGateway**1. This service charges a fixed monthly fee (e.g., ₹300/month) for unlimited transactions. The PaymentGatewayOrchestrator calls the Vyapar REST API to generate a UPI intent link (upi://pay?...). The Flutter frontend receives this string and triggers a deep link app switch, opening Google Pay or PhonePe directly. The funds are settled instantly (T+0) to the company's corporate bank account, bypassing intermediary settlement wallets entirely1.  
Because funds settle directly to the bank, the backend must rely exclusively on cryptographic webhooks as the source of truth for payment success. The gateways secure these webhooks using an HMAC SHA-256 signature1. The Spring Boot application implements a RequestCachingFilter to cache the raw byte array of the incoming HTTP request. The controller extracts this raw array to compute and verify the signature *before* any JSON deserialization occurs2. Once verified, the WebhookProcessingService actively masks Personally Identifiable Information (PII) before persisting the payload to the webhook\_deliveries audit log, ensuring compliance with data privacy laws2.

### **6.3 Synchronizing the Ecosystem via Kafka**

The architecture implements a highly decoupled Event-Driven architecture utilizing Apache Kafka. Services do not make blocking synchronous HTTP calls to one another to signify state changes.  
When a payment succeeds, the Payment Service publishes a PaymentSucceededEvent to the payment-events Kafka topic2. The Order Management Service consumes this topic, transitions the internal cart state, alerts the Kitchen display system, and triggers the LogisticsDispatchService to begin searching the Redis Geospatial cache for available Delivery Executives2.  
To alert the customer and the restaurant regarding these state changes, the platform utilizes a Centralized Notification Service. Services do not call notification REST APIs; instead, they serialize a JSON payload representing a NotificationRequestEvent and publish it to the platform.notifications.dispatch Kafka topic2.

#### **Schema: NotificationRequestEvent**

JSON  
{  
  "eventId": "a73b2c1d-4f90-8b1e-923f",  
  "userId": "user-uuid-reference",  
  "eventName": "ORDER\_DISPATCHED",  
  "channel": "PUSH",  
  "templateParams": \["John Doe", "12 minutes"\],  
  "payload": { "action\_url": "app://delivery/track" }  
}

The Notification Service acts as a Kafka consumer. It queries PostgreSQL to resolve the userId into an FCM token or phone number, checks user opt-out preferences, and routes the message to the appropriate provider (e.g., Firebase, Exotel)2.  
To control communication costs and prevent spam, the Notification Service implements a global rate limiter using the Bucket4j library paired with Redis1. If a user exceeds their notification quota, a RateLimitExceededException is thrown. The Kafka consumer utilizes Spring Kafka's @RetryableTopic for non-blocking retries of transient network errors; however, exceptions like RateLimitExceededException or UserOptedOutException are explicitly excluded from the retry queues to prevent wasted resources and infinite loops2.

### **6.4 Idempotency and Fault Tolerance**

In distributed microservice architectures, network partitions and timeouts are inevitable. If the Order Service times out while requesting a payment intent from the Payment Service, it may automatically retry the request. To prevent catastrophic edge cases—such as generating duplicate orders, double-charging a customer, or dispatching two Delivery Executives to the exact same restaurant—all internal APIs enforce strict idempotency2.  
Clients must pass a unique UUID in the Idempotency-Key HTTP header2. The IdempotencyFilter utilizes Redis-based distributed locking to ensure that if a request with a previously seen key arrives, the operation is skipped, and the cached response of the original successful request is returned2. The locks are designed to automatically release if exceptions occur or 5xx responses are generated, allowing for clean, safe retries2.

## **7\. Conclusion**

Architecting a modern food delivery platform demands severe attention to real-time asynchronous communications, rigid data validation constraints, and optimized cross-platform interfaces.  
By selecting Flutter as the unified UI framework, the organization guarantees high-fidelity, native-level rendering of MapLibre vector maps across Web, iOS, and Android while maintaining single-codebase efficiency. The Delivery Executive application mitigates the chaotic nature of mobile networks by implementing aggressive background WebSocket management, exponential backoff reconnect strategies, and offline SQLite queueing. This ensures the logistics backend receives an uninterrupted stream of telemetry into its Redis spatial structures for sub-second algorithmic dispatch.  
Simultaneously, the Restaurant Partner application is optimized to replace manual, error-prone form-filling with automated KYB verification. Utilizing third-party APIs for OCR data extraction, GSTIN/FSSAI validation, and Cashfree penny drop testing secures the platform against fraud while establishing a completely frictionless, wizard-like onboarding pipeline. Tied together by a robust Java Spring Boot ecosystem leveraging Apache Kafka for outbox pattern event propagation and PostgreSQL/PostGIS for immutable data storage, this architecture provides a highly scalable, idempotent, and fault-tolerant infrastructure capable of managing intense hyper-local logistical demands.

#### **Works cited**

1. Food Delivery App, uploaded:Food Delivery App  
2. SKILL.md  
3. Design a KYB process for eCommerce \- NextLeap, [https://assets.nextleap.app/submissions/07595d94-23fc-490b-b466-83dd9fdd388a\_Design\_a\_KYB\_process\_for\_eCommerce-35d008b1-8d78-40e0-9462-1ac827d153f1.pdf](https://assets.nextleap.app/submissions/07595d94-23fc-490b-b466-83dd9fdd388a_Design_a_KYB_process_for_eCommerce-35d008b1-8d78-40e0-9462-1ac827d153f1.pdf)  
4. How Cross-Platform Development Services Can Transform Your App \- UXDivers, [https://uxdivers.com/de/blog/how-cross-platform-development-services-can-transform-your-app](https://uxdivers.com/de/blog/how-cross-platform-development-services-can-transform-your-app)  
5. Mainstream UI App Development Frameworks in 2026 — A Learning Summary, [https://dqdongg.com/crossplatform/2026/05/15/Mainstream-UI-App-Development-Frameworks.html](https://dqdongg.com/crossplatform/2026/05/15/Mainstream-UI-App-Development-Frameworks.html)  
6. The Seven Most Popular Cross-Platform App Development Frameworks | Kotlin Multiplatform Documentation, [https://kotlinlang.org/docs/multiplatform/cross-platform-frameworks.html](https://kotlinlang.org/docs/multiplatform/cross-platform-frameworks.html)  
7. Best Mobile App Development Languages in 2026 | VirtueNetz, [https://www.virtuenetz.com/blog/best-mobile-app-development-languages/](https://www.virtuenetz.com/blog/best-mobile-app-development-languages/)  
8. MAUI vs Avalonia in 2026: Choosing a Cross-Platform .NET UI Framework | CTCO, [https://www.ctco.blog/posts/maui-vs-avalonia-2026-cross-platform-dotnet-ui/](https://www.ctco.blog/posts/maui-vs-avalonia-2026-cross-platform-dotnet-ui/)  
9. Similar Packages \- MapLibre Flutter, [https://flutter-maplibre.pages.dev/compare/similar-packages/](https://flutter-maplibre.pages.dev/compare/similar-packages/)  
10. Flutter SDK Chatbot Builder \- Conferbot, [https://www.conferbot.com/chatbot/flutter](https://www.conferbot.com/chatbot/flutter)  
11. MapLibre Flutter: Home, [https://flutter-maplibre.pages.dev/](https://flutter-maplibre.pages.dev/)  
12. MapLibre Newsletter February 2026, [https://maplibre.org/news/2026-03-03-maplibre-newsletter-february-2026/](https://maplibre.org/news/2026-03-03-maplibre-newsletter-february-2026/)  
13. Advanced Flutter WebSocket Architecture: 5 Production-Ready Patterns for High-Performance Real-Time Applications \- Ashutosh Kumar, [https://tiwariashuism.medium.com/advanced-flutter-websocket-architecture-5-production-ready-patterns-for-high-performance-real-time-f4f2ce0a5cbc](https://tiwariashuism.medium.com/advanced-flutter-websocket-architecture-5-production-ready-patterns-for-high-performance-real-time-f4f2ce0a5cbc)  
14. What is WebSocket \- Where And How To Use It \- PieHost, [https://piehost.com/websocket/getting-started-with-websocket](https://piehost.com/websocket/getting-started-with-websocket)  
15. Pricing \- Ola Maps API Plans for Businesses & Developers, [https://maps.olakrutrim.com/pricing](https://maps.olakrutrim.com/pricing)  
16. Krutrim announces new pricing and strategic future roadmap for Ola Maps \- Motorindia, [https://www.motorindiaonline.in/krutrim-announces-new-pricing-and-strategic-future-roadmap-for-ola-maps/](https://www.motorindiaonline.in/krutrim-announces-new-pricing-and-strategic-future-roadmap-for-ola-maps/)  
17. Ola Maps \- AI-Powered Maps, Geocoding API & Directions for India \- Krutrim, [https://www.olakrutrim.com/ola-maps](https://www.olakrutrim.com/ola-maps)  
18. Ola Maps Pricing & Reviews 2026 | Techjockey.com, [https://www.techjockey.com/detail/olamaps](https://www.techjockey.com/detail/olamaps)  
19. Why You Should Be Onboarding Your Restaurant Partners \- eduMe, [https://www.edume.com/blog/restaurant-partner-onboarding](https://www.edume.com/blog/restaurant-partner-onboarding)  
20. Software Engineer Resume: Raunak Singh | PDF \- Scribd, [https://www.scribd.com/document/982054650/Raunak-Singh-Resume-1](https://www.scribd.com/document/982054650/Raunak-Singh-Resume-1)  
21. Comprehensive Verification and Analysis Services | PDF \- Scribd, [https://www.scribd.com/document/936599614/List-API-Scoreme](https://www.scribd.com/document/936599614/List-API-Scoreme)  
22. OnGrid API Pricing and Services Overview | PDF | Verification And Validation \- Scribd, [https://www.scribd.com/document/839406698/1698](https://www.scribd.com/document/839406698/1698)  
23. Cashfree for Product Teams: Payments, Payouts & Pricing 2026 | productgrowth.in, [https://productgrowth.in/tools/payments/cashfree/](https://productgrowth.in/tools/payments/cashfree/)  
24. Instant Bank Account Verification Tool with Penny Drop \- Cashfree Payments, [https://www.cashfree.com/bank-account-verification/](https://www.cashfree.com/bank-account-verification/)  
25. Verifying Bank Account & Bank Account Details Instantly \- RazorpayX, [https://razorpay.com/x/bank-account-verification/](https://razorpay.com/x/bank-account-verification/)  
26. Payment Gateway Charges | Lowest Pricing & UPI Payment Gateway Fees in India | Cashfree, [https://www.cashfree.com/payment-gateway-charges/](https://www.cashfree.com/payment-gateway-charges/)