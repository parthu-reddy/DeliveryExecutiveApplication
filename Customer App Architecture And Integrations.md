# **The Customer Application: Conversion, Discovery, and Transaction**

The architecture of a modern food delivery platform requires an intricate synthesis of high-performance frontend rendering, real-time geospatial processing, and fault-tolerant financial orchestration. The Customer Application serves as the critical interface within this event-driven ecosystem, bridging human intent with backend logistics. This exhaustive research report delineates the structural blueprints, functional paradigms, and technical integrations necessary to engineer a frictionless user experience across three core phases: Conversion (registration and authentication), Discovery (geospatial searching and menu browsing), and Transaction (checkout and financial settlement). Supported by a robust Java Spring Boot backend, PostgreSQL with PostGIS, Redis, and Apache Kafka, this document provides a comprehensive guide to building a production-ready client application.

## **Frontend Architectural Framework Selection**

The foundational decision in architecting the Customer Application involves selecting a cross-platform mobile development framework capable of delivering near-native performance across Web, Android, and iOS environments. The contemporary mobile development landscape in 2026 is dominated by three primary contenders: Flutter, React Native, and Kotlin Multiplatform (KMP)1. A rigorous evaluation of these frameworks is required to determine the optimal solution for an application heavily reliant on real-time map rendering, complex polyline drawing, and high-frequency coordinate updates.  
React Native, backed by Meta, relies on actual platform-native UI components and possesses the largest developer ecosystem1. The framework has recently undergone a significant architectural paradigm shift, introducing the New Architecture, which replaces the legacy asynchronous JSON bridge with the JavaScript Interface (JSI), Fabric renderer, and TurboModules5. These advancements enable synchronous native module calls and concurrent rendering, substantially improving cold start times and layout calculation speeds5. However, React Native still encounters performance bottlenecks when executing complex, hardware-accelerated animations and managing heavily dynamic map components on lower-end devices4.  
Kotlin Multiplatform (KMP) has emerged as a formidable enterprise solution, particularly for applications requiring rigorous security, machine learning integrations, and extensive offline logic sharing3. KMP permits developers to share core business logic written in Kotlin across platforms while utilizing fully native UI toolkits—Jetpack Compose for Android and SwiftUI for iOS1. This approach guarantees uncompromised platform fidelity and eliminates the overhead associated with JavaScript bridges9. Nevertheless, maintaining dual user interface codebases inherently increases development effort and time-to-market, contradicting the project's mandate for an efficient, single-codebase UI deployment1.  
Flutter, engineered by Google, utilizes the Dart programming language and operates on a fundamentally different rendering philosophy. Rather than wrapping native components, Flutter utilizes its own high-performance rendering engine (Impeller, replacing the legacy Skia engine) to draw every pixel directly onto the screen canvas1. This guarantees absolute visual consistency across all operating systems and eliminates the unpredictable behavior often associated with bridging layers1. For a food delivery application, which demands silky-smooth 120Hz animations for live driver tracking, complex geospatial interpolation, and highly customized branding, Flutter provides the superior balance of performance and development velocity3. Its compiled nature, utilizing Ahead-Of-Time (AOT) compilation, ensures minimal memory overhead and near-native execution speeds, making it the definitive choice for the Customer Application8.

### **State Management and Clean Architecture Integration**

To prevent architectural degradation and maintain a scalable Flutter codebase as the feature set expands, the application must strictly adhere to Clean Architecture principles integrated with Riverpod 3.016. Older state management solutions, such as the Provider package or BLoC, introduce unnecessary verbosity or rely heavily on the widget tree's BuildContext for dependency resolution18. Riverpod eliminates these dependencies, offering compile-time safety, explicit dependency tracking, and a robust code-generation engine (riverpod\_generator) utilizing @riverpod annotations16.  
Clean Architecture mandates a strict separation of concerns through unidirectional dependency rules, ensuring that business logic remains entirely decoupled from UI frameworks and external data sources17. This architecture is divided into three distinct layers.  
The Domain Layer serves as the core of the application, housing the foundational entities (such as User, Restaurant, and Order), pure business use cases, and abstract repository contracts17. This layer contains absolutely no Flutter or Riverpod dependencies, ensuring complete framework independence and facilitating deterministic unit testing17.  
The Data Layer is responsible for fulfilling the contracts defined in the domain layer. It contains the implementations of the repositories, manages communication with the Java Spring Boot backend via HTTP clients, handles local caching strategies using SQLite or Hive, and performs Data Transfer Object (DTO) serialization17.  
The Presentation Layer acts as the orchestrator, containing the Flutter widgets and Riverpod state controllers17. It utilizes modern Riverpod constructs such as AsyncNotifier and Notifier to bridge the domain use cases with the user interface, reacting automatically to asynchronous data streams and managing loading, success, and error states without polluting the UI code with complex logical branching19.

## **Phase 1: The Conversion Process**

The Conversion phase encompasses the critical initial user onboarding, authentication, and profile generation sequences. The overarching objective is to minimize friction, ensuring that prospective users transition from application launch to a fully authenticated state with minimal manual intervention. This requires sophisticated integration with mobile hardware APIs and the backend Centralized Notification Service.

### **Detailed Use Case: Frictionless Customer Authentication**

The authentication process relies on a passwordless, mobile-first strategy utilizing SMS One-Time Passwords (OTPs). To maximize conversion rates, the application must automatically intercept and read the incoming SMS, eliminating the need for the user to memorize and manually input the code.

| Use Case Element | Architectural Specification |
| :---- | :---- |
| **Use Case Name** | Frictionless SMS OTP Authentication (UC-CONV-01) |
| **Primary Actor** | Anonymous Application User |
| **Trigger** | The user inputs their mobile number and requests access to the platform. |
| **Preconditions** | The Flutter application is installed, the device has an active cellular connection, and the Java backend services are operational. |
| **Main Success Flow** | 1\. The user inputs their mobile number into the Flutter presentation layer. 2\. The application invokes the backend authentication endpoint (POST /api/v1/auth/initiate). 3\. The backend generates a secure OTP, stores the hash in Redis with a Time-To-Live (TTL), and publishes a NotificationRequestEvent to Kafka. 4\. The Notification Service dispatches the SMS via a telecom provider. 5\. The Flutter application, utilizing the Google SMS Retriever API, listens for incoming messages. 6\. The OS intercepts the specific SMS and passes the payload to the application. 7\. The application extracts the OTP and automatically invokes the verification endpoint (POST /api/v1/auth/verify). 8\. The backend issues a JWT session token and the user proceeds to the Discovery phase. |
| **Alternate Flow 1** | *Network Latency/Timeout:* If the SMS is not received within 60 seconds, the application exposes a manual "Resend OTP" function, triggering a secondary Kafka event. |
| **Alternate Flow 2** | *Rate Limit Exceeded:* If the user requests excessive OTPs, the backend RateLimitingService (using Bucket4j and Redis) throws a RateLimitExceededException. The UI displays a localized error message utilizing Riverpod's AsyncError state. |
| **Postconditions** | The user possesses a valid, cryptographically signed JWT stored in the device's secure enclave, and the local Riverpod user state is initialized. |

### **Functional Architecture: Authentication Orchestration**

The seamless execution of this use case depends on a strict contract between the Flutter application, the Google Android operating system, and the Java Spring Boot backend. The Flutter plugin sms\_autofill is utilized to interface with the native Google SMS Retriever API23. This API is uniquely advantageous because it allows the application to read the specific OTP message without prompting the user for broad, invasive READ\_SMS permissions, which severely degrade user trust and conversion rates23.  
For the SMS Retriever API to function, the backend Notification Service must format the outbound message according to rigid cryptographic constraints24. The message payload must not exceed 140 bytes, must optionally begin with a \<\#\> prefix, must clearly state the one-time code, and, most importantly, must terminate with an exact 11-character hash string that cryptographically identifies the specific Android application package and its signing certificate24.

| Interaction Step | Source Component | Destination Component | Protocol / Action | Payload / Description |
| :---- | :---- | :---- | :---- | :---- |
| 1 | Flutter Application | Order Service Backend | HTTP POST /api/v1/auth/initiate | {"phone": "+919876543210"} |
| 2 | Order Service | Kafka Topic (platform.notifications.dispatch) | TCP / Publish Event | JSON representing NotificationRequestEvent containing eventId, userId, channel: SMS, and templateParams29. |
| 3 | Kafka Broker | Notification Service | TCP / Consume Event | The NotificationEventConsumer ingests the message29. |
| 4 | Notification Service | Telecom Gateway (e.g., Fast2SMS / MetaReach) | HTTP POST | Routes the message formatted with the 11-character app hash (e.g., FA+9qCX9VSu)28. |
| 5 | Telecom Gateway | Customer Mobile Device | Cellular Network | SMS Delivery. |
| 6 | Android OS | Flutter Application | SMS Retriever API Broadcast | The OS identifies the 11-character hash and routes the SMS text exclusively to the Flutter app24. |
| 7 | Flutter Application | Order Service Backend | HTTP POST /api/v1/auth/verify | {"phone": "+919876543210", "otp": "123456"} |

The Centralized Notification Service orchestrates this dispatch by resolving the correct provider and evaluating user opt-outs via the UserPreferenceRepository29. It is critical to note that in the Indian telecommunications landscape, all commercial SMS templates must be pre-approved through a Distributed Ledger Technology (DLT) platform managed by major operators (such as Jio or Airtel) to bypass Do Not Disturb (DND) registries and ensure instant, high-priority delivery required for OTPs29.

## **Phase 2: The Discovery Process**

Following successful authentication, the user transitions into the Discovery phase. This segment of the application is deeply reliant on geospatial intelligence, enabling users to pinpoint their exact delivery location, browse relevant restaurant catalogs within a specific radius, and construct a transaction cart.

### **Detailed Use Case: Geospatial Address Resolution**

Accurate address resolution is paramount; a failed or inaccurate delivery location leads directly to aborted transactions and logistical failures. The application integrates with the Ola Maps Integration Service to provide predictive autocomplete and reverse geocoding capabilities29.

| Use Case Element | Architectural Specification |
| :---- | :---- |
| **Use Case Name** | Predictive Address Resolution and Bounding (UC-DISC-01) |
| **Primary Actor** | Authenticated Customer |
| **Trigger** | The user navigates to the location selection interface. |
| **Preconditions** | The user has granted location permissions to the application, or the application defaults to a manual search interface. |
| **Main Success Flow** | 1\. The device captures the current GPS coordinates (latitude, longitude). 2\. The Flutter application invokes the backend proxy endpoint (GET /api/places/reverse-geocode?lat=X\&lng=Y). 3\. The backend interacts with the external Ola Maps API and returns a human-readable address string29. 4\. The user verifies the address and adjusts the map pin if necessary. 5\. The application saves the precise Point(lng, lat) geometry to the user's PostgreSQL profile. |
| **Alternate Flow 1** | *Manual Search:* The user types an address. The frontend sends debounced requests to the Places Autocomplete API (GET /api/places/autocomplete?input=Text), displaying predictive results29. |
| **Postconditions** | A highly precise geospatial point is recorded in the global Riverpod state, enabling radius-based restaurant queries. |

### **Detailed Use Case: Contextual Restaurant and Menu Browsing**

Once the geospatial anchor is established, the application must query the backend to retrieve a contextual list of available restaurants. This process requires high-speed spatial bounding box queries within the PostgreSQL database, leveraging the PostGIS extension29.

| Use Case Element | Architectural Specification |
| :---- | :---- |
| **Use Case Name** | Radius-Bound Restaurant Discovery (UC-DISC-02) |
| **Primary Actor** | Authenticated Customer |
| **Trigger** | The user confirms their delivery location. |
| **Preconditions** | The GEOMETRY(Point, 4326\) coordinate is active in the application state. |
| **Main Success Flow** | 1\. The frontend requests the restaurant feed, passing the user's coordinates. 2\. The Java backend performs an optimized ST\_DWithin spatial query using GiST indexes on the restaurants table to locate entities within a defined operational radius (e.g., 5.0 kilometers)29. 3\. The backend calculates dynamic Estimated Times of Arrival (ETAs) by querying the Ola Maps Distance Matrix API, caching responses in Redis to mitigate redundant external calls29. 4\. The UI displays the filtered restaurant catalog. 5\. The user selects a restaurant, browses the menu, and manipulates the local cart. |
| **Postconditions** | The user has populated a local, in-memory cart with selected menu items, ready for financial validation. |

### **Functional Architecture: Cart State Management**

The cart management system is maintained entirely within the Flutter application's memory during the Discovery phase to eliminate network latency and ensure instantaneous user feedback. Riverpod's Notifier class is perfectly suited for this task, as it enforces immutability; the cart state cannot be modified directly, but rather through explicitly defined methods (e.g., addItem, removeItem) that generate a new state object, triggering a reactive UI rebuild only where necessary16.  
When the user is ready to proceed, the application sends the complete cart payload to the Order Service (POST /api/v1/cart/validate). The Java backend verifies item availability, ensures pricing consistency (preventing client-side tampering), and calculates applicable taxes and delivery fees before authorizing the transition to the Transaction phase29.

## **Phase 3: The Transaction Engine**

The Transaction phase is the most critical and complex component of the application architecture. It is responsible for converting a validated cart into a legally binding financial ledger entry. This phase demands strict idempotency, sophisticated integration with external payment gateways, and highly secure, asynchronous webhook verifications to prevent fraudulent exploitation.

### **External API Strategy: Vyapar Gateway Integration**

Historically, food delivery platforms have relied on traditional payment aggregators such as Razorpay or Cashfree. These providers operate on a percentage-based model, typically skimming 1.95% to 2% plus 18% GST from every transaction30. Even when utilizing the Unified Payments Interface (UPI)—which the Indian government mandates must carry a zero Merchant Discount Rate (MDR) for bank-to-bank transfers—these aggregators still impose their platform processing fees30. For a delivery application operating on thin logistical margins, surrendering 2% of gross merchandise value is financially detrimental31.  
To optimize unit economics, the architectural blueprint mandates the integration of **Vyapar Gateway**, a flat-fee UPI infrastructure provider31. Vyapar bypasses percentage-based fees entirely, charging a fixed monthly subscription (e.g., ₹300 per month) for unlimited transaction volume32. Furthermore, Vyapar operates purely as a technology routing layer rather than a middleman wallet; funds are settled instantly in real-time (T+0 settlement) directly into the corporate bank account32. This direct settlement model dramatically improves cash flow visibility and eliminates the standard T+2 holding periods imposed by traditional gateways34.

### **Detailed Use Case: Secure Checkout and Orchestration**

The checkout process requires tightly coupled synchronization between the frontend application, the backend Order Service, and the Payment Service orchestrator.

| Use Case Element | Architectural Specification |
| :---- | :---- |
| **Use Case Name** | Cryptographic Intent Generation (UC-TRANS-01) |
| **Primary Actor** | Authenticated Customer |
| **Trigger** | The user initiates the checkout sequence from the cart interface. |
| **Preconditions** | The cart is validated, and the exact financial total is calculated. |
| **Main Success Flow** | 1\. The Flutter application submits an order creation request (POST /api/v1/orders). To prevent double charges resulting from network retries, a unique UUID is passed in the Idempotency-Key header29. 2\. The Order Service persists the cart to PostgreSQL with a status of CREATED. 3\. The Order Service synchronously invokes the internal Payment Service (PaymentGatewayOrchestrator\#createOrder), providing the internalOrderId and the exact amountInInr29. 4\. The Payment Service, utilizing the VyaparGatewayStrategy, calls the Vyapar REST API to generate a payment session35. 5\. The backend returns a specific UPI intent string (e.g., upi://pay?pa=merchant@bank\&pn=App...) to the frontend29. 6\. The Flutter application uses the url\_launcher package to execute a deep link app switch, seamlessly opening the user's preferred UPI application (PhonePe, Google Pay, Paytm) with all transaction details pre-filled32. |
| **Alternate Flow 1** | *Idempotency Lock:* If the client retries the request with the same Idempotency-Key, the backend IdempotencyFilter (utilizing Redis distributed locks) blocks the concurrent call and returns the existing gateway order ID29. |
| **Postconditions** | A PaymentIntent record is created in the database with a status of INITIATED, awaiting asynchronous webhook confirmation. |

### **Detailed Use Case: Webhook Verification and Fulfillment**

Because the frontend client application operates in an untrusted environment, it must never be relied upon to confirm payment success29. Fulfillment relies entirely on secure, server-to-server cryptographic webhooks.

| Use Case Element | Architectural Specification |
| :---- | :---- |
| **Use Case Name** | Asynchronous Webhook Processing (UC-TRANS-02) |
| **Primary Actor** | Vyapar Gateway / Backend Payment Service |
| **Trigger** | The customer successfully authorizes the UPI payment in their banking app. |
| **Preconditions** | The PaymentIntent exists in the database in an INITIATED state. |
| **Main Success Flow** | 1\. Vyapar Gateway transmits an HTTP POST webhook to the Payment Service (POST /api/v1/webhooks/vyapar)29. 2\. The webhook payload includes an HMAC SHA-256 signature in the HTTP headers33. 3\. The Payment Service intercepts the stream using a RequestCachingFilter to capture the raw, unparsed byte array29. 4\. The system calculates a matching hash using the raw bytes and the merchant secret, utilizing a constant-time comparison algorithm to thwart timing attacks29. 5\. Upon successful validation, the WebhookProcessingService masks any incoming PII (scrubbing emails and phone numbers) and persists the payload to the webhook\_deliveries audit log29. 6\. The database updates the PaymentIntent to PAID within an atomic transaction. 7\. The Payment Service publishes a PaymentSucceededEvent to the payment-events Kafka topic29. 8\. The Order Service consumes the event, updates the order to CONFIRMED, and initiates logistics dispatch29. |
| **Alternate Flow 1** | *Signature Failure:* If the computed HMAC hash does not match, the system rejects the request to prevent fraud and replay attacks, generating a security alert33. |
| **Postconditions** | The financial ledger is updated, the order is confirmed, and the logistics engine is activated. |

### **Functional Architecture: Refund Reconciliation under T+0 Settlement**

A critical architectural consideration when utilizing a direct-to-bank T+0 settlement provider like Vyapar is the absence of a centralized gateway wallet balance30. When using traditional gateways, refunds are simply deducted from the gateway's holding pool. In the direct-to-bank model, the backend must operate a highly sophisticated refund reconciliation engine.  
When a user cancels an order, the system registers a pending collection deduction30. The backend intercepts the next sequential incoming application order and routes those funds to offset the refund liability. If the transaction pipeline is empty, the system defaults to structured reverse IMPS text routes via connected banking APIs to push the funds back to the original source account30. Strict database constraints (chk\_refund\_limits) are enforced at the PostgreSQL level to guarantee that refund disbursements mathematically cannot exceed the originally captured transaction amount, protecting the platform from double-refund exploitation29.

## **Phase 4: Post-Transaction \- Logistics Telemetry and Live Tracking**

Once the financial transaction is secured and the order is confirmed, the Customer Application transitions into its final state: a live telemetry and logistical tracking interface. The user experience during this phase is defined by the application's ability to render highly accurate geospatial data, execute smooth marker animations, and maintain robust, low-latency WebSocket connections with the backend infrastructure.

### **Detailed Use Case: Real-Time Fleet Tracking**

The tracking interface provides the customer with absolute visibility into the location and estimated arrival of their assigned delivery executive.

| Use Case Element | Architectural Specification |
| :---- | :---- |
| **Use Case Name** | Real-Time Vector Map Visualization (UC-POST-01) |
| **Primary Actor** | Authenticated Customer / Delivery Executive |
| **Trigger** | The Order Service assigns a delivery executive and transitions the order status to DISPATCHED. |
| **Preconditions** | The Delivery Executive application is broadcasting GPS telemetry. |
| **Main Success Flow** | 1\. The Delivery Executive app continually broadcasts GPS coordinates to the backend WebSocket (ws://localhost:8080/tracking) every 3 to 5 seconds29. 2\. The Maps Integration Service utilizes Project Reactor (Sinks.Many and Flux) to reactively ingest these high-frequency pings, buffering them and flushing them in batches to Redis Geospatial indexes to prevent PostgreSQL I/O exhaustion29. 3\. The Customer Application establishes a WebSocket connection, receiving the real-time coordinates of the assigned driver. 4\. The Flutter application fetches the turn-by-turn routing polyline from the Ola Maps API (GET /api/logistics/route)29. 5\. The application renders the vector map, the route polyline, and the driver marker using the flutter-maplibre-gl package39. 6\. As new telemetry payloads arrive, the application animates the marker smoothly along the route. |
| **Alternate Flow 1** | *Driver Disconnection:* If the backend ceases receiving pings for a defined threshold, the Customer App displays a "Reconnecting to driver location" state, relying on the last known Redis coordinate. |
| **Postconditions** | The order is marked DELIVERED based on proximity geofencing and executive confirmation, terminating the tracking session. |

### **Functional Architecture: MapLibre and Coordinate Interpolation**

A common pitfall in food delivery application development is the implementation of naive state updates for map markers. Because GPS coordinates are only polled and broadcast every few seconds, simply updating the marker's latitude and longitude results in a jarring, teleporting visual experience that shatters the illusion of real-time movement40.  
To deliver a premium, fluid user experience comparable to industry leaders, the Flutter application implements a sophisticated coordinate interpolation algorithm over a vector tile map. The system utilizes MapLibre GL for Flutter (maplibre\_gl), an open-source, vendor-neutral implementation of the Mapbox Vector Tile (MVT) standard, which allows for high-performance rendering without relying on proprietary, cost-prohibitive map tokens38.  
When a new coordinate payload is received via the WebSocket, the Flutter application does not instantly snap the marker to the new location. Instead, it calculates the mathematical distance between the current marker position and the incoming GPS coordinate40. The required movement is divided into multiple micro-steps (e.g., 50 distinct steps)40. Utilizing a Flutter Ticker or AnimationController, the marker's latitude and longitude are updated incrementally over a predefined duration (e.g., 1000 milliseconds)40. This aligns the movement with the physical device's 60Hz or 120Hz display refresh rate.  
Simultaneously, the marker's rotation alignment is calculated and animated to match the bearing of the active polyline route41. This multi-layered interpolation logic masks the inherent latency of GPS polling and cellular network transmission, creating a smooth, continuous movement of the driver icon along the rendered streets40.

## **Conclusion**

The Customer Application is a highly sophisticated orchestration of cross-platform frontend engineering and event-driven backend microservices. By leveraging Flutter and the Riverpod Clean Architecture framework, the platform guarantees a pixel-perfect, highly responsive user interface capable of managing complex state mutations and executing 120Hz geospatial animations without performance degradation.  
The strategic integration of the Vyapar Gateway eliminates restrictive percentage-based transaction margins, fundamentally improving the platform's unit economics through flat-fee, direct-to-bank T+0 settlements. Furthermore, the reliance on an Apache Kafka event-driven backend ensures that authentication, payment fulfillment, logistics dispatch, and push notifications are processed asynchronously and reliably. Strict adherence to architectural mandates—including raw-byte HMAC verification for financial webhooks, PostGIS indexing for rapid spatial queries, Project Reactor backpressure for WebSocket telemetry, and continuous coordinate interpolation for MapLibre markers—results in a robust, scalable, and production-ready system capable of satisfying the exhaustive demands of a modern food delivery ecosystem.

#### **Works cited**

1. Cross platform mobile development in 2026: 5 frameworks compared honestly \- Drizz, [https://www.drizz.dev/post/cross-platform-mobile-development](https://www.drizz.dev/post/cross-platform-mobile-development)  
2. 5 Best Cross Platform Frameworks for App Dev in 2026, [https://platform.uno/articles/best-cross-platform-frameworks-2026/](https://platform.uno/articles/best-cross-platform-frameworks-2026/)  
3. Kotlin Multiplatform vs Flutter vs React Native : What to Choose in 2026 \- TechQware, [https://www.techqware.com/blog/kotlin-multiplatform-vs-flutter-vs-react-native-what-to-choose](https://www.techqware.com/blog/kotlin-multiplatform-vs-flutter-vs-react-native-what-to-choose)  
4. Kotlin Multiplatform VS Flutter VS React Native, [https://guarana-technologies.com/blog/kotlin-multiplatform-vs-flutter-vs-react-native](https://guarana-technologies.com/blog/kotlin-multiplatform-vs-flutter-vs-react-native)  
5. React Native Performance Optimization: The 2026 Playbook \- RapidNative, [https://www.rapidnative.com/blogs/react-native-performance-optimization-2026-playbook](https://www.rapidnative.com/blogs/react-native-performance-optimization-2026-playbook)  
6. React Native Architecture Trends: Top Developments for Modern Apps \- Instamobile, [https://instamobile.io/blog/react-native-paradigm-shift/](https://instamobile.io/blog/react-native-paradigm-shift/)  
7. React Native New Architecture Migration Guide (2026): Step-by-Step \- AgileSoftLabs Blog, [https://www.agilesoftlabs.com/blog/2026/03/react-native-new-architecture-migration](https://www.agilesoftlabs.com/blog/2026/03/react-native-new-architecture-migration)  
8. Cross-Platform Mobile App Development: Comparison of Android and iOS Frameworks \- ManTech Publications, [https://admin.mantechpublications.com/index.php/JoAIT/article/download/1956/1245](https://admin.mantechpublications.com/index.php/JoAIT/article/download/1956/1245)  
9. Kotlin Multiplatform vs Flutter vs React Native: The Best Choice for FinTech Apps in 2026, [https://www.weblineglobal.com/blog/kotlin-multiplatform-vs-flutter-vs-react-native-fintech/](https://www.weblineglobal.com/blog/kotlin-multiplatform-vs-flutter-vs-react-native-fintech/)  
10. Flutter vs React Native vs Kotlin Multiplatform: 2026 \- Nibble Software, [https://www.nibblesoftware.com/flutter-vs-react-native-vs-kotlin-multiplatform/](https://www.nibblesoftware.com/flutter-vs-react-native-vs-kotlin-multiplatform/)  
11. Flutter vs Other Frameworks: Complete Comparison Guide 2026, [https://flutterindia.in/blog/flutter-vs-other-frameworks-complete-comparison-guide/](https://flutterindia.in/blog/flutter-vs-other-frameworks-complete-comparison-guide/)  
12. Framework Comparisons \- Skip, [https://skip.dev/compare/](https://skip.dev/compare/)  
13. How to Choose the Right Tech Stack for Your Food Delivery App \- StackFood, [https://stackfood.app/blog/right-tech-stack-for-your-food-delivery-app/](https://stackfood.app/blog/right-tech-stack-for-your-food-delivery-app/)  
14. Performance Analysis of Cross-Platform Frameworks: A Real-World Comparison of Flutter, React Native, and Native Development | by Tarun Choudhary | Medium, [https://medium.com/@tarun1940c/performance-analysis-of-cross-platform-frameworks-a-real-world-comparison-of-flutter-react-93492b07a0cd](https://medium.com/@tarun1940c/performance-analysis-of-cross-platform-frameworks-a-real-world-comparison-of-flutter-react-93492b07a0cd)  
15. React Native vs Flutter vs Kotlin Multiplatform: A CTO's Decision Guide (2026) \- Medium, [https://medium.com/@avirootinfosolution/react-native-vs-flutter-vs-kotlin-multiplatform-a-ctos-decision-guide-2026-21dc43277cf0](https://medium.com/@avirootinfosolution/react-native-vs-flutter-vs-kotlin-multiplatform-a-ctos-decision-guide-2026-21dc43277cf0)  
16. Flutter Riverpod 2.0: The Ultimate Guide \- Code With Andrea, [https://codewithandrea.com/articles/flutter-state-management-riverpod/](https://codewithandrea.com/articles/flutter-state-management-riverpod/)  
17. Flutter Clean Architecture with Riverpod | by Romaan Khan \- Medium, [https://medium.com/@romaanofficial/flutter-clean-architecture-with-riverpod-d496775c06f6](https://medium.com/@romaanofficial/flutter-clean-architecture-with-riverpod-d496775c06f6)  
18. Mastering Flutter State Management with Riverpod | by Luke C \- Medium, [https://medium.com/@lukemcar/mastering-flutter-state-management-with-riverpod-85086f8ceeaf](https://medium.com/@lukemcar/mastering-flutter-state-management-with-riverpod-85086f8ceeaf)  
19. Flutter State Management in 2026: Why I Built atomic\_flutter \- Arkar's Blog, [https://arkar.dev/flutter-state-management-in-2026-why-i-built-atomic\_flutter/](https://arkar.dev/flutter-state-management-in-2026-why-i-built-atomic_flutter/)  
20. What Is Riverpod in Flutter: Examples, How to Use it & Best Practices | LeanCode, [https://leancode.co/glossary/riverpod-in-flutter](https://leancode.co/glossary/riverpod-in-flutter)  
21. ssoad/flutter\_riverpod\_clean\_architecture \- GitHub, [https://github.com/ssoad/flutter\_riverpod\_clean\_architecture](https://github.com/ssoad/flutter_riverpod_clean_architecture)  
22. Flutter Riverpod Clean Architecture: The Ultimate Production-Ready Template for Scalable Apps \- DEV Community, [https://dev.to/ssoad/flutter-riverpod-clean-architecture-the-ultimate-production-ready-template-for-scalable-apps-gdh](https://dev.to/ssoad/flutter-riverpod-clean-architecture-the-ultimate-production-ready-template-for-scalable-apps-gdh)  
23. sms\_autofill | Flutter package \- Pub.dev, [https://pub.dev/packages/sms\_autofill](https://pub.dev/packages/sms_autofill)  
24. Automatic SMS Verification with the SMS Retriever API \- Google for Developers, [https://developers.google.com/identity/sms-retriever/overview](https://developers.google.com/identity/sms-retriever/overview)  
25. sms\_otp\_auto\_verify \- Flutter package in PIN, OTP & Password Field category, [https://fluttergems.dev/packages/sms\_otp\_auto\_verify/](https://fluttergems.dev/packages/sms_otp_auto_verify/)  
26. GitHub \- Tkko/flutter\_smart\_auth: Flutter package for listening SMS code on Android, suggesting phone number, email, saving a credential., [https://github.com/Tkko/flutter\_smart\_auth](https://github.com/Tkko/flutter_smart_auth)  
27. sms\_autofill 0.0.1 | Flutter package \- Pub.dev, [https://pub.dev/packages/sms\_autofill/versions/0.0.1](https://pub.dev/packages/sms_autofill/versions/0.0.1)  
28. SMS Autofill (OTP) in Flutter \- Medium, [https://medium.com/@jitenbasnet7/sms-autofill-otp-in-flutter-5dd63e8543f2](https://medium.com/@jitenbasnet7/sms-autofill-otp-in-flutter-5dd63e8543f2)  
29. SKILL.md  
30. Food Delivery App, uploaded:Food Delivery App  
31. How to Accept UPI Payments on Your Website Without a 2% Gateway Fee | VyaparGateway, [https://vyapargateway.com/blog/accept-upi-payments-website-without-2-percent-fee/](https://vyapargateway.com/blog/accept-upi-payments-website-without-2-percent-fee/)  
32. VyaparGateway: 0% Fee UPI Payment Gateway India, [https://vyapargateway.com/](https://vyapargateway.com/)  
33. VyaparGateway vs UPIGateway: Why Pay ₹1299 When Similar Features Start at ₹300?, [https://vyapargateway.com/blog/vyapargateway-vs-upigateway-comparison/](https://vyapargateway.com/blog/vyapargateway-vs-upigateway-comparison/)  
34. Payment Gateway Pricing & UPI Gateway Charges (0% Fee) \- VyaparGateway, [https://vyapargateway.com/pricing/](https://vyapargateway.com/pricing/)  
35. UPI Payment Gateway API Integration Guide (2026) \- VyaparGateway, [https://vyapargateway.com/developers/](https://vyapargateway.com/developers/)  
36. Integrate Payment Gateway with Business Software Easily \- Vyapar App, [https://vyaparapp.in/blog/integrate-payment-gateway-business-software/](https://vyaparapp.in/blog/integrate-payment-gateway-business-software/)  
37. Free UPI Intent Link Generator 2026 \- VyaparGateway, [https://vyapargateway.com/tools/upi-intent-link-generator](https://vyapargateway.com/tools/upi-intent-link-generator)  
38. maplibre | Flutter package \- Pub.dev, [https://pub.dev/packages/maplibre](https://pub.dev/packages/maplibre)  
39. Flutter MapLibre GL \- GitHub, [https://github.com/maplibre/flutter-maplibre-gl](https://github.com/maplibre/flutter-maplibre-gl)  
40. How We Built Real-Time Moving Markers in Flutter (Like Zomato & Rapido)… Here's What Actually Worked | by Ankit Mehra | Medium, [https://medium.com/@ankii8946/how-we-built-real-time-moving-markers-in-flutter-like-zomato-rapido-heres-what-actually-14ddbf40b844](https://medium.com/@ankii8946/how-we-built-real-time-moving-markers-in-flutter-like-zomato-rapido-heres-what-actually-14ddbf40b844)  
41. Marker \- MapLibre GL JS, [https://maplibre.org/maplibre-gl-js/docs/API/classes/Marker/](https://maplibre.org/maplibre-gl-js/docs/API/classes/Marker/)  
42. animated\_marker | Flutter package \- Pub.dev, [https://pub.dev/packages/animated\_marker](https://pub.dev/packages/animated_marker)  
43. Animate a point along a route \- MapLibre GL JS, [https://maplibre.org/maplibre-gl-js/docs/examples/animate-a-point-along-a-route/](https://maplibre.org/maplibre-gl-js/docs/examples/animate-a-point-along-a-route/)