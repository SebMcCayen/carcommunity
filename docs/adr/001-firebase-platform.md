# ADR-001: Replace Azure with Firebase as backend platform

## Status

Accepted

## Date

2026-06-29

## Context

The initial architecture planned a production-only Azure deployment: Azure Container Apps for the API and admin web, Azure Database for PostgreSQL, Azure Blob Storage, Azure Notification Hubs, and Microsoft Entra ID for authentication. Prisma was the ORM.

The project targets an initial audience of 20–30 active users with a maximum operating budget of SEK 500 per month. Running containerised workloads on Azure does not fit this cost constraint at low user volumes. Azure services carry fixed infrastructure costs that are disproportionate to MVP scale.

Firebase provides a managed, consumption-based platform that scales to near-zero when idle and covers authentication, realtime database, file storage, serverless functions, hosting, and push notifications in a single SDK ecosystem. The cost profile aligns with an MVP budget of SEK 500 per month at the expected usage level.

## Decision

Replace the Azure stack with Firebase:

| Previous | Replacement |
|---|---|
| Azure Container Apps | Cloud Functions for Firebase (2nd gen) |
| Azure Database for PostgreSQL | Cloud Firestore + Firebase Realtime Database |
| Azure Blob Storage | Cloud Storage for Firebase |
| Azure Notification Hubs | Firebase Cloud Messaging (FCM) |
| Microsoft Entra ID | Firebase Authentication |
| Azure Bicep infrastructure-as-code | Firebase project configuration via Firebase CLI |
| Prisma ORM | Firebase Admin SDK + Firestore SDK |
| Admin web on Azure Container Apps | Firebase Hosting |

### Runtime

- Cloud Functions for Firebase 2nd generation, Firebase-supported Node.js runtime, TypeScript.
- Firebase Emulator Suite for local development.

### Authentication

- iOS: Sign in with Apple through Firebase Authentication.
- Android: Google Sign-In through Firebase Authentication.
- Admin web: Google Sign-In through Firebase Authentication.
- Admin authorization uses server-managed Firebase custom claims.
- Provider account linking is not included in the MVP.

### Mobile applications

The current mobile application is `apps/mobile` (React Native / Expo). The target architecture plans separate native codebases:

- `apps/ios`: Swift and SwiftUI (planned).
- `apps/android`: Kotlin and Jetpack Compose (planned).

The migration from `apps/mobile` to separate native applications is a separate migration task and is not included in this decision.

Both target applications must provide equivalent functionality. Mobile platform parity remains mandatory.

React Native, Flutter, Kotlin Multiplatform, and other shared mobile runtimes are not introduced in the target native architecture.

### Budget

Maximum operating budget: SEK 500 per month. Infrastructure choices must remain within this constraint.

## Consequences

### Positive

- Near-zero cost at 20–30 active users; costs scale with actual usage.
- Managed realtime support (Firestore listeners, Realtime Database) removes the need for custom WebSocket infrastructure.
- Firebase Authentication handles provider token verification and identity binding, reducing custom auth code.
- Firebase Emulator Suite enables full offline local development without cloud costs.
- Firebase Hosting provides CDN-backed delivery for the admin web with zero server management.
- Push notifications through FCM work on both iOS and Android from a single API.

### Negative

- Firestore data model differs from relational PostgreSQL; queries are limited compared to SQL.
- Firestore does not support joins or complex aggregations natively; read patterns must be designed upfront.
- Cloud Functions have cold-start latency; latency-sensitive paths may require warmup strategies.
- Vendor lock-in to Google Cloud / Firebase increases; migration away would be significant.
- Existing application code using Prisma and PostgreSQL must be migrated in a future task.

### Neutral

- Prisma schema and existing service code are not deleted in this change; they remain until migration tasks replace them.
- Firebase project provisioning is deferred to a dedicated task.
- No application feature changes are included in this decision.
