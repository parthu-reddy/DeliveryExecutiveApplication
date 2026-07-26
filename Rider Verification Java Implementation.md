# **Architecture and Implementation Blueprint for Delivery Executive Verification and Telemetry Security**

The rapid expansion of the quick-commerce and food delivery sectors necessitates robust, frictionless, and highly secure onboarding pipelines for delivery executives. Platforms operate as algorithmic managers, overseeing a decentralized fleet of independent contractors, managing millions of dynamic data points daily1. Because delivery executives act as the physical interface between the platform, the restaurant partner, and the end consumer, their verification is a matter of strict legal compliance, operational efficiency, and physical safety1.  
Unlike restaurant partners, who are required to possess valid Food Safety and Standards Authority of India (FSSAI) licenses and Goods and Services Tax Identification Numbers (GSTIN), delivery executives are exempt from these specific commercial regulations2. Instead, their verification focuses entirely on identity validation, criminal background verification, vehicular compliance, and financial routing accuracy2. The operational imperative for these platforms is speed; modern quick-commerce models demand that a rider progresses from the initial application download to their first successful delivery in under twenty-four hours to prevent unfulfilled delivery slots and lost revenue4.  
This report provides an exhaustive, production-ready architectural blueprint detailing how high-volume food delivery applications verify riders. The analysis covers static document checks, biometric identity validation, and continuous runtime operational security. Furthermore, this document serves as a comprehensive technical specification, including database schemas and modern Java (Spring Boot) implementation guides, designed to be executed by artificial intelligence development agents to construct a highly resilient logistics backend.

## **Phase 1: Pre-Onboarding Document and Identity Verification**

To ensure the safety of customers and the integrity of the platform ecosystem, a multi-tiered background verification process is initiated the moment a prospective delivery partner submits their application5. This process is largely automated via integrations with third-party verification vendors such as IDfy, Signzy, Surepass, and Karza, which possess the infrastructure to communicate directly with state registries6.

### **Primary Identity and Background Verification Protocols**

The foundation of the onboarding process requires the validation of the applicant's core identity. This is achieved by extracting data from government-issued identity documents, primarily the Aadhaar card and the Permanent Account Number (PAN) card3. The minimum requirements for a delivery partner typically include being at least eighteen years of age, possessing an Android smartphone with at least 2GB of RAM, and maintaining an active bank account3.  
Optical Character Recognition (OCR) APIs extract text fields from uploaded documents, including the name, date of birth, address, and document number10. However, modern architectural standards dictate that mere OCR extraction is insufficient, as it is vulnerable to digital forgery and photoshopped documents. The extracted data must be programmatically cross-referenced with government databases in real-time to ensure the document has not been altered11. Advanced systems utilize artificial intelligence to detect signs of tampering, template mismatches, and printed photocopies10.  
Simultaneously, platforms employ background verification partners to conduct automated criminal history checks and address verification6. The system scans digital First Information Report (FIR) records, sexual offender databases, and court registries to identify any pending legal cases or criminal history that would disqualify the candidate6. While traditional verification in multinational corporations can be a slow, manual process, food delivery platforms utilize asynchronous APIs to perform these checks in the background, allowing for conditional early activation of the driver profile while the final court record searches complete over a span of two to seven days3.

### **Vehicular Compliance: The Sarathi and Vahan Ecosystem**

For delivery executives utilizing motorized two-wheelers, vehicular compliance is strictly enforced. The applicant must possess a valid driving license and a Vehicle Registration Certificate (RC)3. The backend system integrates directly with the Ministry of Road Transport and Highways (MoRTH) databases via authorized API gateways to validate these credentials without manual human intervention.  
The Sarathi API is utilized to verify the driver's legal authorization to operate a vehicle12. The architecture mandates that the backend submits the 16-digit Driving License number alongside the applicant's Date of Birth12. In return, the API delivers a JSON payload detailing the license holder's name, the issue and expiry dates, the issuing Regional Transport Office (RTO), and the authorized vehicle classes13. This granular data extraction is critical; it allows the platform to algorithmically ensure that a driver registered on the platform with a motorcycle is legally permitted to drive a Motor Cycle With Gear (MCWG), rather than holding only a learner's permit or a non-transport authorization13.  
Conversely, the Vahan API is employed for the verification of the vehicle itself14. By submitting the vehicle's license plate number to the Vahan registry, the backend retrieves the Registration Certificate details15. This process confirms the vehicle's fitness, insurance status, hypothecation (financier details), and ownership15. Guaranteeing that the vehicle used for commercial logistics complies with state laws protects the platform from secondary liability in the event of an accident or traffic violation during an active delivery shift.  
To effectively design the backend integration for these transport APIs, development teams must account for specific request and response structures, as well as stringent error handling.

| API Service | Data Input Required | Key Output Data Points | Error State Triggers |
| :---- | :---- | :---- | ----- |
| **Sarathi (Driving License)** | 16-digit DL Number, Date of Birth (YYYY-MM-DD) | Holder Name, Validity Dates, Vehicle Class (e.g., MCWG), RTO Details | HTTP 400 (Regex mismatch for DL format), HTTP 502 (Gateway Timeout) |
| **Vahan (Vehicle RC)** | Vehicle Registration Number (License Plate) | Owner Name, Insurance Expiry, Fitness Status, Hypothecation | HTTP 404 (Vehicle not found), Rate Limit Exceeded |

### **Financial Settlement Verification and Fraud Prevention**

Ensuring accurate wage payouts is critical for retaining a gig-economy workforce. Delivery executives rely on frequent, accurate payouts for their earnings, and any friction in this process leads to significant churn. If a delivery executive inputs an incorrect bank account number or IFSC code during registration, the automated weekly or daily payout will fail, resulting in a frustrating reversal cycle that can take up to five days to resolve2.  
To circumvent this logistical bottleneck, platforms implement an automated financial verification process known as the "Penny Drop"2. The backend utilizes a payment gateway API to instantly deposit a nominal amount (typically ₹1.00) via the Immediate Payment Service (IMPS) into the applicant's provided bank account2.  
When the transaction is successful, the banking system returns the exact beneficiary name registered to that specific account. This introduces a critical security layer: the returned bank beneficiary name must be algorithmically matched against the verified name extracted from the applicant's PAN or Aadhaar card2. This ensures that the bank account legally belongs to the delivery executive and prevents bad actors from routing platform earnings to unauthorized third-party accounts, thereby mitigating systemic financial fraud and money laundering risks.

## **Phase 2: Algorithmic Name Reconciliation and Fuzzy Matching**

A significant technical challenge arises when reconciling the name returned by the banking infrastructure with the name extracted from the identity document. In the Indian demographic context, names are frequently transliterated differently across various government and financial institutions. For example, a single individual might be registered as "Mohammed Sreejith" on their Aadhaar card, "Md. Srijith" on their PAN card, and "Sreejith M" in their bank account. Relying on exact string matching (a 1:1 binary comparison) will result in an unacceptably high rate of false negatives, failing legitimate applicants and drastically increasing manual review overhead for the operations team.  
To resolve this, the backend architecture must employ advanced fuzzy string-matching algorithms, which quantify the similarity between two strings and return a confidence score17. The selection of the specific algorithm is paramount to the success of the verification engine.

### **Levenshtein Distance vs. Jaro-Winkler Similarity**

In the domain of Anti-Money Laundering (AML) and Know Your Customer (KYC) compliance, two primary character-based algorithms are evaluated: Levenshtein Distance and Jaro-Winkler Similarity17.  
The Levenshtein Distance calculates the minimum number of single-character edits—specifically insertions, deletions, and substitutions—required to transform one string into another17. The algorithm treats all character positions equally, meaning an error at the beginning of a string carries the exact same mathematical penalty as an error at the end17. This makes Levenshtein highly effective for comparing longer strings where accuracy throughout the entire string matters, such as complex corporate entity names or detailed physical addresses17. For instance, transforming "Mohammad" to "Muhammad" requires two substitutions, resulting in an edit distance of two17.  
However, for matching individual human names, particularly those subject to phonetic spelling variations and transliteration inconsistencies, the Jaro-Winkler Similarity algorithm is vastly superior17. Jaro-Winkler produces a normalized score between 0.0 (no similarity) and 1.0 (an exact match)19. The core advantage of Jaro-Winkler is that it applies a scaling factor to award a higher mathematical weight to strings that match from the beginning (the prefix)17. Because typographical errors and transliteration shifts typically occur in the middle or end of a name (e.g., "Cindy" vs. "Cyndi", "Catherine" vs. "Katharine"), emphasizing the initial characters dramatically reduces false negatives18.  
By implementing Jaro-Winkler similarity via mature libraries such as Apache Commons Text, the backend can calculate a precise similarity score21. The system architecture leverages this score through configurable thresholds: if the similarity score exceeds an upper threshold (e.g., 0.85), the name match is automatically approved, and the onboarding proceeds seamlessly18. If the score falls between the upper threshold and a lower threshold (e.g., 0.70), it is flagged and routed to a human operations dashboard for manual review18. Scores below the lower threshold result in an immediate automated rejection, prompting the user to upload a different bank account.

| Feature | Levenshtein Distance | Jaro-Winkler Similarity |
| :---- | :---- | :---- |
| **Core Metric** | Edit count (insertions, deletions, substitutions) | Percentage of matched characters & transpositions |
| **Output Type** | Absolute Integer (Distance) | Normalized Double (0.0 to 1.0 Score) |
| **Positional Weighting** | Equal weight across the entire string | Heavily weights prefix/initial character matches |
| **Optimal Use Case** | Long strings, corporate names, addresses | Short strings, individual human names, typos |

## **Phase 3: Runtime and Operational Verification**

Verification does not conclude upon successful onboarding. Once a delivery executive is active on the platform, continuous runtime verification is required to mitigate fraud, prevent account lending, and detect geographic spoofing. Food delivery platforms utilize algorithmic management to continuously assess performance, enforce compliance, and maintain supply-side integrity1.

### **Biometric Liveness and Proof-of-Presence**

A pervasive issue in the gig economy logistics sector is account sharing, a practice where an authorized, verified driver illicitly rents or shares their active platform account with an unverified individual1. This compromises customer safety, circumvents the background verification process, and disrupts the platform's predictive routing algorithms, which rely on the historical speed and efficiency metrics of the specific verified driver to calculate precise ETAs1.  
To combat this vulnerability, platforms mandate random selfie verifications. Before logging in for a shift, or unpredictably during an active delivery run, the mobile application prompts the driver to capture a real-time selfie23.  
This process involves two distinct technological hurdles: Liveness Detection and Facial Recognition (Face Match). Liveness detection ensures that the image captured is of a physical, living person present at the time of the prompt, successfully rejecting photographs of photographs, video playbacks, or sophisticated digital deepfakes11. Advanced APIs verify the three-dimensional depth and micro-movements to confirm presence8.  
Once liveness is confirmed, the captured selfie is transmitted to a Face Match API, which utilizes AI-driven decision engines to compare the live selfie against the baseline photograph extracted from the driver's Aadhaar or Driving License during the initial onboarding phase8. If the confidence score is high (typically requiring 99% accuracy for automated approval), the driver is permitted to accept orders25. If the verification fails, the account is temporarily suspended, preventing unauthorized access23.  
However, facial recognition systems in high-friction, real-world environments present unique edge cases. Industry reports highlight instances where drivers are locked out of their accounts due to algorithmic failures caused by poor ambient lighting, the wearing of protective face masks, heavy sunglasses, or significant changes in facial hair since their onboarding photograph was taken24. To mitigate these operational blockages, the backend architecture must seamlessly handle failure states, providing clear UI feedback to the driver to remove masks or seek better lighting, while allowing a predetermined number of retry attempts before initiating a hard lock requiring manual intervention27.

### **Geographic Integrity and GPS Spoofing Detection**

Delivery platforms rely entirely on precise geospatial telemetry to calculate accurate ETAs, dispatch orders to the closest optimal driver, and calculate distance-based payouts. A common fraud vector involves delivery executives using third-party "Fake GPS" or mock location applications to spoof their geographic coordinates28. This allows a driver resting miles away to appear algorithmically as though they are waiting directly outside a high-volume restaurant, thereby unfairly intercepting lucrative orders ahead of honest drivers.  
The mobile application and the backend architecture must work in tandem to detect and neutralize mock locations. On the client side (Android devices), the application must inspect every location update payload before transmitting it to the server. Prior to Android 12 (API level 31), developers utilize the Location.isFromMockProvider() method to determine if the coordinate was injected by a software spoofing application rather than derived from the physical GPS hardware28. For modern devices running Android 12 and above, the updated Location.isMock() API is utilized to achieve the exact same security verification31.  
However, client-side checks can be bypassed by sophisticated actors who root their Android devices to intercept and rewrite the API responses at the operating system level. Therefore, the backend architecture must independently calculate the velocity and distance between sequential location pings to identify impossible physics. If a driver's telemetry indicates they have traveled five kilometers in ten seconds—an impossible velocity in urban traffic—the backend heuristic engine must flag the telemetry as spoofed, discard the coordinates from the active dispatch radius, and penalize the account for fraudulent activity.

## **Phase 4: Step-by-Step Java Backend Implementation Blueprint**

The following sections provide a highly detailed, production-ready implementation guide designed for an AI development agent to construct the verification and telemetry engine. The technical stack utilizes Java 17+, Spring Boot 3.x, and PostgreSQL equipped with the PostGIS extension for advanced geospatial querying.

### **Step 1: Database Schema Architecture (PostgreSQL DDL)**

The database schema must securely store identity documents, handle high-frequency geospatial telemetry, and log continuous verification events to maintain an immutable audit trail. The following schema utilizes standard relational structures alongside precise database-level ENUMs for strict state management.

SQL  
\-- Enable PostGIS for Advanced Geospatial routing and proximity querying  
CREATE EXTENSION IF NOT EXISTS postgis;

\-- Enum types for strict application state management  
CREATE TYPE verification\_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'MANUAL\_REVIEW');  
CREATE TYPE document\_type AS ENUM ('AADHAAR', 'PAN', 'DRIVING\_LICENSE', 'RC', 'SELFIE');  
CREATE TYPE vehicle\_class AS ENUM ('BICYCLE', 'MCWG', 'LMV', 'EV\_TWO\_WHEELER');

\-- Core Delivery Executive Entity Table  
CREATE TABLE delivery\_executives (  
    executive\_id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    first\_name VARCHAR(100) NOT NULL,  
    last\_name VARCHAR(100) NOT NULL,  
    phone\_number VARCHAR(15) UNIQUE NOT NULL,  
    current\_status verification\_status DEFAULT 'PENDING',  
    vehicle\_type vehicle\_class NOT NULL,  
    is\_active BOOLEAN DEFAULT FALSE,  
    created\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP,  
    updated\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

\-- Bank Details & IMPS Penny Drop Verification Engine  
CREATE TABLE executive\_bank\_details (  
    bank\_id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    executive\_id UUID REFERENCES delivery\_executives(executive\_id) ON DELETE CASCADE,  
    account\_number VARCHAR(50) NOT NULL,  
    ifsc\_code VARCHAR(20) NOT NULL,  
    bank\_registered\_name VARCHAR(255),  
    penny\_drop\_status verification\_status DEFAULT 'PENDING',  
    name\_match\_score NUMERIC(4,3), \-- Stores the Jaro-Winkler floating point score  
    verified\_at TIMESTAMP WITH TIME ZONE,  
    UNIQUE(executive\_id)  
);

\-- External API Document Verification Logs (Sarathi, Vahan, Signzy)  
CREATE TABLE executive\_documents (  
    document\_id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    executive\_id UUID REFERENCES delivery\_executives(executive\_id) ON DELETE CASCADE,  
    doc\_type document\_type NOT NULL,  
    document\_number VARCHAR(100) NOT NULL,  
    document\_url VARCHAR(512), \-- Secure URL to Object Storage (e.g., AWS S3, Cloudflare R2)  
    api\_verification\_status verification\_status DEFAULT 'PENDING',  
    api\_raw\_response JSONB, \-- Stores the exact unparsed JSON payload for audit and debugging  
    expiry\_date DATE,  
    created\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

\-- Biometric Runtime Verification Logs (Selfie Checks)  
CREATE TABLE biometric\_verifications (  
    verification\_id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    executive\_id UUID REFERENCES delivery\_executives(executive\_id),  
    selfie\_url VARCHAR(512) NOT NULL,  
    confidence\_score NUMERIC(4,3) NOT NULL,  
    is\_live BOOLEAN NOT NULL,  
    verification\_time TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

\-- High-Frequency Telemetry Log (Insert Heavy Table)  
CREATE TABLE telemetry\_logs (  
    log\_id BIGSERIAL PRIMARY KEY,  
    executive\_id UUID REFERENCES delivery\_executives(executive\_id),  
    location GEOGRAPHY(POINT, 4326) NOT NULL, \-- PostGIS geospatial SRID 4326 format  
    speed\_kmh NUMERIC(5,2),  
    is\_mock\_location BOOLEAN NOT NULL DEFAULT FALSE,  
    recorded\_at TIMESTAMP WITH TIME ZONE NOT NULL  
);

\-- Explicit Indexing for performance under heavy concurrency  
CREATE INDEX idx\_telemetry\_executive ON telemetry\_logs(executive\_id);  
CREATE INDEX idx\_telemetry\_location ON telemetry\_logs USING GIST(location);  
CREATE INDEX idx\_docs\_executive ON executive\_documents(executive\_id);

### **Step 2: Maven Dependencies Configuration**

To execute fuzzy string matching, robust HTTP client requests, and complex PostGIS operations efficiently, the project requires specific, production-tested dependencies. The pom.xml configuration is detailed below.

XML  
\<dependencies\>  
    \<\!-- Core Spring Boot Web & Data JPA Starters \--\>  
    \<dependency\>  
        \<groupId\>org.springframework.boot\</groupId\>  
        \<artifactId\>spring-boot-starter-web\</artifactId\>  
    \</dependency\>  
    \<dependency\>  
        \<groupId\>org.springframework.boot\</groupId\>  
        \<artifactId\>spring-boot-starter-data-jpa\</artifactId\>  
    \</dependency\>  
      
    \<\!-- Apache Commons Text for Jaro-Winkler Similarity Algorithm \--\>  
    \<\!-- Utilized for fuzzy matching the Bank Beneficiary Name against KYC Name \--\>  
    \<dependency\>  
        \<groupId\>org.apache.commons\</groupId\>  
        \<artifactId\>commons-text\</artifactId\>  
        \<version\>1.10.0\</version\>  
    \</dependency\>

    \<\!-- Hibernate Spatial for native PostGIS mapping and Geometry objects \--\>  
    \<dependency\>  
        \<groupId\>org.hibernate.orm\</groupId\>  
        \<artifactId\>hibernate-spatial\</artifactId\>  
        \<version\>6.2.5.Final\</version\>  
    \</dependency\>  
      
    \<\!-- Optimized PostgreSQL Driver \--\>  
    \<dependency\>  
        \<groupId\>org.postgresql\</groupId\>  
        \<artifactId\>postgresql\</artifactId\>  
        \<scope\>runtime\</scope\>  
    \</dependency\>  
\</dependencies\>

### **Step 3: Fuzzy Name Matching Service (Jaro-Winkler)**

When the Penny Drop API returns the registered bank name, it must be programmatically compared against the KYC name to ensure financial security without creating operational friction. The implementation utilizes the JaroWinklerSimilarity class from the Apache Commons Text library, which is specifically designed to handle Indian name transliteration variances21.

Java  
package com.fooddelivery.verification.service;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;  
import org.springframework.stereotype.Service;  
import lombok.extern.slf4j.Slf4j;

@Slf4j  
@Service  
public class NameMatchingService {

    // Threshold configurations balancing security and operational scale  
    private static final double AUTO\_APPROVE\_THRESHOLD \= 0.85;  
    private static final double MANUAL\_REVIEW\_THRESHOLD \= 0.70;  
      
    // Singleton instantiation of the Jaro-Winkler algorithm \[cite: 21, 34\]  
    private final JaroWinklerSimilarity jaroWinkler \= new JaroWinklerSimilarity();

    /\*\*  
     \* Executes fuzzy comparison of the KYC identity name against the Bank Account beneficiary name.  
     \*   
     \* @param kycName Name extracted via OCR from PAN or Aadhaar  
     \* @param bankName Name returned directly by the IMPS Penny Drop API  
     \* @return MatchResult DTO containing the absolute score and system status  
     \*/  
    public MatchResult evaluateNameMatch(String kycName, String bankName) {  
        if (kycName \== null || bankName \== null) {  
            throw new IllegalArgumentException("Comparison names cannot be null");  
        }

        // Data Normalization: Force lowercase and strip all non-alphabetic special characters  
        String normalizedKyc \= kycName.trim().toLowerCase().replaceAll("\[^a-z\\\\s\]", "");  
        String normalizedBank \= bankName.trim().toLowerCase().replaceAll("\[^a-z\\\\s\]", "");

        // Apply Jaro-Winkler calculation yielding a double between 0.0 and 1.0 \[cite: 21, 33\]  
        Double score \= jaroWinkler.apply(normalizedKyc, normalizedBank);  
          
        log.info("Jaro-Winkler evaluation for KYC \[{}\] and Bank \[{}\]: {}", normalizedKyc, normalizedBank, score);

        VerificationStatus status;  
        if (score \>= AUTO\_APPROVE\_THRESHOLD) {  
            status \= VerificationStatus.APPROVED;  
        } else if (score \>= MANUAL\_REVIEW\_THRESHOLD) {  
            status \= VerificationStatus.MANUAL\_REVIEW;  
        } else {  
            status \= VerificationStatus.REJECTED;  
        }

        return new MatchResult(score, status);  
    }  
      
    // Modern Java Record DTO for immutable data transfer  
    public record MatchResult(Double score, VerificationStatus status) {}  
    public enum VerificationStatus { APPROVED, REJECTED, MANUAL\_REVIEW }  
}

### **Step 4: Driving License (Sarathi) API Integration Service**

The backend architecture must communicate with external e-governance API gateways (such as Surepass or Signzy) to validate the applicant's Driving License against the central Sarathi registry. The JSON request payload dictates the submission of the 16-digit dlnumber and dob12. Modern Spring Boot applications should utilize the RestClient interface for these synchronous HTTP calls to ensure thread safety and optimal connection pooling.

Java  
package com.fooddelivery.verification.service;

import com.fasterxml.jackson.databind.JsonNode;  
import org.springframework.beans.factory.annotation.Value;  
import org.springframework.stereotype.Service;  
import org.springframework.web.client.RestClient;  
import org.springframework.http.MediaType;  
import lombok.RequiredArgsConstructor;  
import lombok.extern.slf4j.Slf4j;

@Slf4j  
@Service  
@RequiredArgsConstructor  
public class DrivingLicenseVerificationService {

    private final RestClient restClient;  
      
    @Value("${verification.sarathi.api.url}")  
    private String sarathiApiUrl;  
      
    @Value("${verification.sarathi.api.key}")  
    private String apiKey;

    /\*\*  
     \* Executes real-time validation of the Driving License against the Sarathi Database.  
     \*   
     \* @param dlNumber 16-digit alphanumeric DL Number (e.g., RJ14 20110012345\)  
     \* @param dateOfBirth Format constraint: yyyy-mm-dd  
     \* @return DLVerificationResponse DTO encapsulating the registry data  
     \*/  
    public DLVerificationResponse verifyDrivingLicense(String dlNumber, String dateOfBirth) {  
          
        // Construct the strict JSON Request Body required by the provider  
        String requestBody \= String.format("{\\"dlnumber\\": \\"%s\\", \\"dob\\": \\"%s\\"}", dlNumber, dateOfBirth);  
          
        try {  
            // Execute synchronous POST request to external Sarathi integration gateway  
            JsonNode response \= restClient.post()  
                .uri(sarathiApiUrl)  
                .header("Authorization", "Bearer " \+ apiKey)  
                .contentType(MediaType.APPLICATION\_JSON)  
                .body(requestBody)  
                .retrieve()  
                .body(JsonNode.class);  
                  
            return parseSarathiResponse(response);  
              
        } catch (Exception e) {  
            log.error("Network or Authentication failure communicating with Sarathi API for DL: {}", dlNumber, e);  
            throw new ExternalVerificationException("DL Verification API unavailable. Initiate fallback queue.");  
        }  
    }

    private DLVerificationResponse parseSarathiResponse(JsonNode responseNode) {  
        // Evaluate the JSON structure based on provider specifications for HTTP 400 bad formats  
        if (responseNode.has("error") && "true".equals(responseNode.get("error").asText())) {  
            log.warn("Sarathi API rejected payload: {}", responseNode.get("message").asText());  
            return new DLVerificationResponse(false, null, null, responseNode.get("message").asText());  
        }  
          
        // Extract relevant fields assuming successful HTTP 200 payload  
        JsonNode data \= responseNode.get("response").get(0).get("response");  
        String holderName \= data.get("licOj").get("name").asText();  
        String vehicleClass \= data.get("licOj").get("cov").asText(); // E.g., MCWG  
          
        return new DLVerificationResponse(true, holderName, vehicleClass, "Success");  
    }  
      
    public record DLVerificationResponse(boolean isValid, String name, String vehicleClass, String message) {}  
}

### **Step 5: Runtime Telemetry and Anti-Spoofing Controller**

When the delivery executive's mobile application broadcasts geographic location data, the JSON payload must explicitly include a boolean flag indicating if the location was derived from a mock provider (isFromMockProvider on legacy Android systems or isMock on Android 12+)28.  
If GPS spoofing is detected, the backend should log the security violation and silently discard the coordinate to prevent false ETA updates to the customer, while returning a standard HTTP 200 OK so the malicious client is unaware it has been detected.

Java  
package com.fooddelivery.telemetry.controller;

import org.springframework.http.ResponseEntity;  
import org.springframework.web.bind.annotation.\*;  
import lombok.RequiredArgsConstructor;  
import lombok.extern.slf4j.Slf4j;  
import java.util.UUID;

@Slf4j  
@RestController  
@RequestMapping("/api/v1/telemetry")  
@RequiredArgsConstructor  
public class TelemetryController {

    private final TelemetryIngestionService telemetryService;

    /\*\*  
     \* High-frequency ingestion endpoint receiving continuous location pings from the Driver App.  
     \*/  
    @PostMapping("/sync")  
    public ResponseEntity\<Void\> syncLocation(@RequestBody LocationPayload payload,   
                                             @RequestHeader("X-Executive-ID") UUID executiveId) {  
                                                   
        // 1\. Immediate OS-Level Mock Location Security Check  
        if (payload.isMockLocation()) {  
            log.warn("SECURITY BREACH: Geographic Spoofing detected for Executive ID: {}", executiveId);  
            telemetryService.flagAccountForSpoofing(executiveId, payload);  
            // Return 200 OK to prevent app from retrying or altering spoofing behavior, discard data in backend  
            return ResponseEntity.ok().build();   
        }  
          
        // 2\. Server-Side Velocity and Heuristic Check (Defense in Depth)  
        boolean isVelocityValid \= telemetryService.validateVelocity(executiveId, payload);  
        if (\!isVelocityValid) {  
            log.warn("SECURITY ALERT: Impossible physics/velocity detected for Executive ID: {}", executiveId);  
            return ResponseEntity.ok().build();  
        }

        // 3\. Persist valid telemetry to PostGIS and update Redis memory layer for dispatching  
        telemetryService.updateDriverLocation(executiveId, payload);  
          
        return ResponseEntity.ok().build();  
    }  
}

// Immutable DTO representing the payload serialized by the mobile application  
record LocationPayload(  
    double latitude,   
    double longitude,   
    double speedKmh,   
    boolean isMockLocation, // Extracted directly from Android Location.isFromMockProvider() or isMock() \[cite: 29, 32\]  
    long timestampMs  
) {}

## **Conclusion**

The architecture governing the onboarding and operational monitoring of delivery executives must delicately balance ultra-low friction user experiences with uncompromising regulatory, legal, and security compliance. By entirely decoupling the parent brand regulations (such as FSSAI and GSTIN) from the logistics fleet, platforms can drastically accelerate driver acquisition timelines.  
Implementing API-driven validation against sovereign state repositories like Sarathi and Vahan guarantees vehicular legality and protects the platform from operational liabilities. Concurrently, utilizing IMPS penny drop mechanisms coupled with the mathematically nuanced Jaro-Winkler similarity algorithm guarantees financial integrity without subjecting operations to the high false-negative rejection rates inherent in strict character matching.  
Finally, enforcing continuous, real-time runtime protocols—such as AI-driven biometric liveness detection and rigid OS-level GPS spoofing identification—protects the platform's predictive routing algorithms and ensures a consistently secure environment for end consumers. By utilizing the provided PostgreSQL database schemas and Spring Boot service implementations, an artificial intelligence development agent can rapidly construct a highly scalable, robust, and production-ready logistics verification engine capable of managing millions of daily active drivers.

#### **Works cited**

> 1. Algorithmic Management of Workers through Food Delivery Platforms in India, [https://www.researchgate.net/publication/390277643\_Algorithmic\_Management\_of\_Workers\_through\_Food\_Delivery\_Platforms\_in\_India](https://www.researchgate.net/publication/390277643_Algorithmic_Management_of_Workers_through_Food_Delivery_Platforms_in_India)  
> 2. Food Delivery App, uploaded:Food Delivery App  
> 3. Zomato Bike Delivery Partner: Registration, Requirements & Earnings Explained, [https://alphareach.tech/blog/zomato-bike-delivery-job/](https://alphareach.tech/blog/zomato-bike-delivery-job/)  
> 4. Quick Commerce Workforce Verification: BGV for Delivery & Gig Workers \- Springverify Blog, [https://in.springverify.com/blog/quick-commerce-workforce-verification/](https://in.springverify.com/blog/quick-commerce-workforce-verification/)  
> 5. How to Join Swiggy Delivery Boy \- KVB Staffing Solution, [https://www.kvb-group.com/blog-details/how-to-join-swiggy-delivery-boy](https://www.kvb-group.com/blog-details/how-to-join-swiggy-delivery-boy)  
> 6. What you must know about background verification in MNCs \- IDfy, [https://www.idfy.com/blog/background-verification-in-mncs/](https://www.idfy.com/blog/background-verification-in-mncs/)  
> 7. KYC Verification in India: Top 10 Providers Compared (2026) \- Signzy, [https://www.signzy.com/blogs/top-10-kyc-verification-solution-providers-in-india](https://www.signzy.com/blogs/top-10-kyc-verification-solution-providers-in-india)  
> 8. Face verification with Aadhaar \- Surepass, [https://surepass.io/face-verification-with-aadhaar/](https://surepass.io/face-verification-with-aadhaar/)  
> 9. Become Zomato Delivery Partner \- Vihu Jobs, [https://www.vihu.com/in/en/blogarticle/become-zomato-delivery-partner](https://www.vihu.com/in/en/blogarticle/become-zomato-delivery-partner)  
> 10. Driver's License Verification Ultimate Guide: 26 FAQs Answered \- Signzy, [https://www.signzy.com/blogs/drivers-license-verification-ultimate-guide](https://www.signzy.com/blogs/drivers-license-verification-ultimate-guide)  
> 11. ID Verification | Global Document Authentication & Face Match \- Signzy, [https://www.signzy.com/use-cases/identity-verification](https://www.signzy.com/use-cases/identity-verification)  
> 12. ULIP-SARATHI API Integration Guide | PDF | User (Computing) | Json \- Scribd, [https://www.scribd.com/document/702509581/ULIP-SARATHI-Integration-Requirement](https://www.scribd.com/document/702509581/ULIP-SARATHI-Integration-Requirement)  
> 13. Driving License Verification API Explained: Complete Business Guide \- Noble Web Studio, [https://www.noblewebstudio.com/blog/driving-license-verification-api/](https://www.noblewebstudio.com/blog/driving-license-verification-api/)  
> 14. DriverConnect: On-Demand Operator Platform | PDF | Truck | Loader, [https://www.scribd.com/document/957441626/Driver-Operator-on-Demand-Platform](https://www.scribd.com/document/957441626/Driver-Operator-on-Demand-Platform)  
> 15. RC Verification: Stop Vehicle Loan Fraud Before Sanction \- Hyperverge.Co, [https://hyperverge.co/blog/what-is-rc-verification/](https://hyperverge.co/blog/what-is-rc-verification/)  
> 16. RC Advance Verification API – Verify Vehicle RC Details Instantly, [https://www.idspay.in/rc-advance-verification-api](https://www.idspay.in/rc-advance-verification-api)  
> 17. Jaro-Winkler vs. Levenshtein in AML Screening: Choosing the Right Algorithm \- Flagright, [https://www.flagright.com/post/jaro-winkler-vs-levenshtein-choosing-the-right-algorithm-for-aml-screening](https://www.flagright.com/post/jaro-winkler-vs-levenshtein-choosing-the-right-algorithm-for-aml-screening)  
> 18. Fuzzy Matching 101: The Complete Guide to Accurate Data Matching \[2026\] \- Data Ladder, [https://dataladder.com/fuzzy-matching-101/](https://dataladder.com/fuzzy-matching-101/)  
> 19. CAiSE Forum 2015 \- CEUR-WS.org, [https://ceur-ws.org/Vol-1367/CAiSE2015Forum-complete.pdf](https://ceur-ws.org/Vol-1367/CAiSE2015Forum-complete.pdf)  
> 20. Fuzzy Name Matching Techniques | Babel Street, [https://www.babelstreet.com/blog/fuzzy-name-matching-techniques](https://www.babelstreet.com/blog/fuzzy-name-matching-techniques)  
> 21. JaroWinklerSimilarity (Apache Commons Text 1.15.1-SNAPSHOT API), [https://commons.apache.org/proper/commons-text/apidocs/org/apache/commons/text/similarity/JaroWinklerSimilarity.html](https://commons.apache.org/proper/commons-text/apidocs/org/apache/commons/text/similarity/JaroWinklerSimilarity.html)  
> 22. Apache Commons Text \- APOTHEM, [https://apothem.blog/apache-commons-text.html](https://apothem.blog/apache-commons-text.html)  
> 23. Gojek launches facial recognition login for drivers, citing hacking prevention \- KrASIA, [https://kr-asia.com/gojek-launches-facial-recognition-login-for-drivers-citing-hacking-prevention](https://kr-asia.com/gojek-launches-facial-recognition-login-for-drivers-citing-hacking-prevention)  
> 24. Report 2391 \- AI Incident Database, [https://incidentdatabase.ai/reports/2391/](https://incidentdatabase.ai/reports/2391/)  
> 25. Selfie Verification API \- Surepass, [https://surepass.io/selfie-verification-api/](https://surepass.io/selfie-verification-api/)  
> 26. Face Match API \- Signzy, [https://www.signzy.com/fintech-apis/face-match-api](https://www.signzy.com/fintech-apis/face-match-api)  
> 27. Why is there an error to remove my mask while clicking a selfie? | Dhan Support, [https://dhan.co/support/account-opening/kyc-documents/why-is-there-an-error-to-remove-my-mask-while-clicking-a-selfie/](https://dhan.co/support/account-opening/kyc-documents/why-is-there-an-error-to-remove-my-mask-while-clicking-a-selfie/)  
> 28. How to Detect Fake GPS and Mock Location in Android Apps: A Developer's Security Guide, [https://blog.anmolthedeveloper.com/how-to-detect-fake-gps-and-mock-location-in-android-apps-a-developers-security-guide](https://blog.anmolthedeveloper.com/how-to-detect-fake-gps-and-mock-location-in-android-apps-a-developers-security-guide)  
> 29. The geo-spoofing epidemic: how mock location apps are corrupting, [https://www.gogig.in/blog/geo-spoofing-epidemic-mock-location-apps-india/](https://www.gogig.in/blog/geo-spoofing-epidemic-mock-location-apps-india/)  
> 30. smarques84/MockLocationDetector: An android library to help detect mock locations, [https://github.com/smarques84/MockLocationDetector](https://github.com/smarques84/MockLocationDetector)  
> 31. Xememex — A place for thinking, [https://manuals.annafreud.org/dickon/](https://manuals.annafreud.org/dickon/)  
> 32. Diff \- 93e063328a5cec76549ed0d120885ef5abdae5e8^2..93e063328a5cec76549ed0d120885ef5abdae5e8 \- platform/system/update\_engine \- Git at Google \- Android GoogleSource, [https://android.googlesource.com/platform/system/update\_engine/+/93e063328a5cec76549ed0d120885ef5abdae5e8%5E2..93e063328a5cec76549ed0d120885ef5abdae5e8/](https://android.googlesource.com/platform/system/update_engine/+/93e063328a5cec76549ed0d120885ef5abdae5e8%5E2..93e063328a5cec76549ed0d120885ef5abdae5e8/)  
> 33. Source code \- Apache Commons, [https://commons.apache.org/proper/commons-text/apidocs/src-html/org/apache/commons/text/similarity/JaroWinklerSimilarity.html](https://commons.apache.org/proper/commons-text/apidocs/src-html/org/apache/commons/text/similarity/JaroWinklerSimilarity.html)