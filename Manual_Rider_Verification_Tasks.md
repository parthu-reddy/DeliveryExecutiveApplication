# Manual Tasks for Rider Verification Implementation

While the software architecture handles the automation of rider verification, several operational and administrative tasks cannot be implemented in software alone and require manual intervention or business-level decisions.

## 1. Vendor Selection and Contract Negotiation
- **KYC & Background Verification Vendors:** Identify and sign contracts with third-party verification vendors (e.g., IDfy, Signzy, Surepass, Karza).
- **API Keys and Credentials:** Procure production API keys and configure them securely (e.g., in AWS Secrets Manager or HashiCorp Vault) for Sarathi, Vahan, Penny Drop, and Biometric services.

## 2. Operations Dashboard and Manual Review Team
- **Manual Review Queue:** For name matching scores falling between the `MANUAL_REVIEW_THRESHOLD` (0.70) and `AUTO_APPROVE_THRESHOLD` (0.85), an operations team needs to manually review the documents and make a final decision.
- **Biometric Hard Locks:** When facial recognition fails repeatedly (e.g., due to lighting, masks, or facial hair changes) and the account is "hard locked", customer support/operations staff must manually verify the rider through a video call or physical verification process.

## 3. Infrastructure Provisioning
- **Secure Object Storage:** Manually provision and configure secure buckets (like AWS S3 or Cloudflare R2) with appropriate IAM roles and CORS policies for storing sensitive documents (Aadhaar, PAN, DL, Selfies).
- **PostGIS Extension:** Ensure the PostgreSQL database instance is provisioned with the PostGIS extension installed and configured.

## 4. Legal and Compliance Audits
- **Data Privacy (DPDP Act):** Consult legal teams to ensure the collection, storage, and processing of Aadhaar, PAN, and Biometric data comply with India's Digital Personal Data Protection (DPDP) Act and other regional data privacy laws.
- **Data Retention Policies:** Define manual or automated rules for purging rejected or inactive rider documents after the legally mandated retention period.

## 5. Physical Edge Case Resolution
- **GPS Spoofing Disputes:** When a driver is flagged and penalized for impossible velocity or mock locations, provide a manual dispute resolution process in case of device GPS hardware glitches.
