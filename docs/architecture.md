# Architecture

## Overview

> **Note:** This document describes the live architecture. The migration decided in
> [ADR-001](adr/001-firebase-platform.md) is complete — see
> [Removed legacy stack](#removed-legacy-stack) below.

carcommunity is a monorepo targeting native mobile clients, an admin web client, and a Firebase backend as the system of truth. The architecture targets a production-only MVP on Firebase, with strong control over privacy, subscription entitlement, moderation, and operational safety.

Expected initial usage: 20–30 active users. Maximum operating budget: SEK 500 per month.

```text
apps/ios     (Swift / SwiftUI)     ─┐
apps/android (Kotlin / Compose)    ─┤──> Firebase backend ───> Cloud Firestore
apps/admin   (React + Vite web)         ─┘         │               Firebase Realtime Database
                                               │               Cloud Storage for Firebase
                                               ├── Firebase Authentication
                                               ├── Cloud Functions (Firebase-supported Node.js + TypeScript)
                                               ├── Firebase Cloud Messaging
                                               ├── Firebase App Check
                                               ├── Firebase Hosting (admin web)
                                               └── Feature flags + operational controls
```

## Architecture goals

- Keep one source of truth in Firebase backend for security-critical and business-critical state.
- Ship MVP safely in production-only Firebase without separate dev/staging cloud environments.
- Minimize privacy risk by default, especially for location and partner analytics.
- Support modular growth for social, partner, and event capabilities.
- Maintain high performance on mobile-first user journeys.
- Stay within the SEK 500 per month operating budget.

## Monorepo structure

```text
.
├── apps/
│   ├── ios/            # Swift / SwiftUI native iOS app (in scope per ADR-002; not scaffolded yet)
│   ├── android/        # Kotlin / Jetpack Compose native Android app
│   └── admin/          # React + Vite admin web app (hosted on Firebase Hosting)
├── functions/          # Cloud Functions for Firebase
├── firebase/           # Security Rules, Firestore indexes, RTDB rules
├── contracts/          # Language-neutral cross-platform contracts
├── packages/
│   └── shared/         # TypeScript contracts consumed by the admin web app
├── scripts/            # Repository tooling
├── docs/
│   └── adr/            # Architecture decision records
└── .github/
```

## Application boundaries

- **apps/ios**: native iOS UX (Swift / SwiftUI), map rendering, client-side purchase initiation, realtime consumption.
- **apps/android**: native Android UX (Kotlin / Jetpack Compose), map rendering, client-side purchase initiation, realtime consumption.
- **apps/admin**: moderation, partner management, billboard approval, operational dashboards. React + Vite SPA hosted on Firebase Hosting.
- **functions**: Cloud Functions for Firebase — authentication verification, subscription verification, entitlements, authorization, business rules, realtime coordination, persistence, integrations.
- **Cloud Firestore**: durable storage for users, entitlements, moderation state, saved drives, partner aggregates, and operational metadata.
- **Firebase Realtime Database**: ephemeral realtime state — live location, active drive sessions, presence.

## Mobile app architecture

Two separate native applications:

- **apps/ios**: Swift and SwiftUI, Swift Concurrency, StoreKit 2, Core Location, Keychain, Mapbox Maps SDK for iOS, Sign in with Apple through Firebase Authentication.
- **apps/android**: Kotlin and Jetpack Compose, Coroutines/Flow, Google Play Billing, Android location APIs, Android Keystore, Mapbox Maps SDK for Android, Google Sign-In through Firebase Authentication.

Both applications must provide equivalent user-facing functionality. Mobile platform parity is mandatory. See `.github/instructions/mobile-platform-parity.instructions.md`.

Both apps use Mapbox as the mapping provider for MVP.

Both apps call Cloud Functions for trusted operations. They use Firebase SDKs for Firestore, Realtime Database, FCM, and App Check.

## Admin web architecture

- **apps/admin**: React + Vite SPA, Google Sign-In through Firebase Authentication.
- Admin authorization uses server-managed Firebase custom claims verified by Cloud Functions.
- Focus areas:
  - user moderation (blocking/suspension),
  - partner and billboard administration,
  - partner statistics (aggregated only),
  - operational controls and feature rollout.

## Backend architecture

- Cloud Functions for Firebase 2nd generation, Firebase-supported Node.js runtime, TypeScript.
- Functions are organized by domain: auth, entitlement, moderation, social features, location, notifications, integrations.
- Firebase Emulator Suite is used for local development.
- Backend is source of truth for:
  - auth identity binding,
  - admin role (via custom claims),
  - subscription entitlement,
  - live location visibility,
  - blocking/suspension,
  - Kronpoäng,
  - partner statistics,
  - admin actions.

## Data architecture

- **Cloud Firestore**: primary durable store.
  - Core domains: identity and auth subject mapping, subscription entitlements (`member_monthly`), moderation and safety state, social/group/event entities, saved drives, partner entities and aggregate metrics, operational collections (feature flags, idempotency, audit logs).
- **Firebase Realtime Database**: ephemeral realtime state.
  - Live location latest-state records with short TTL behavior.
  - Active drive session state.
  - Presence and connection state.
- Live location is not stored as long-term history.

## Authentication architecture

- iOS: Sign in with Apple through Firebase Authentication.
- Android: Google Sign-In through Firebase Authentication.
- Admin web: Google Sign-In through Firebase Authentication.
- Firebase Authentication verifies provider identity tokens.
- Stable provider subject (`uid`) is the canonical identity key; email is not identity.
- Admin authorization uses server-managed Firebase custom claims set by Cloud Functions.
- Provider account linking is not included in the MVP.

```text
Client login (Apple / Google)
    -> provider token
    -> Firebase Authentication verifies with provider
    -> Firebase uid is canonical identity
    -> custom claims set by Cloud Function for admin role
```

## Subscription architecture

- Purchases are initiated on client via platform-native Apple/Google purchase systems.
- Backend verifies receipt/token with provider.
- Backend stores entitlement as internal `member_monthly`.
- Entitlement checks happen server-side for protected features.

## Authorization and entitlement checks

- Central authorization middleware/policies in backend.
- Effective access is computed from:
  - authenticated identity,
  - role (admin/user),
  - moderation state (blocked/suspended),
  - entitlement state (`member_monthly`),
  - feature flag state.
- Clients never decide final access; they only render based on backend responses.

## Live location architecture

- Location sharing is session-based and explicit.
- Store **latest location only** during active sharing.
- Short TTL expiration for active location record.
- No automatic historical location timeline.
- “Hide me now” immediately removes latest location and closes sharing session.

## Realtime architecture

- Realtime is provided by **Firebase Realtime Database** listeners and **Cloud Firestore** snapshot listeners.
- Realtime channels support live location visibility, chat updates, and group-driving state updates.
- Cloud Functions enforce auth and entitlement before writing to realtime paths.
- Firebase Security Rules enforce read access controls on Realtime Database and Firestore.
- Fan-out is coordinated server-side to keep privacy controls centralized.

## Event chat architecture

- Event chat runs through Firestore persisted messages and Firestore snapshot listeners for realtime delivery.
- Cloud Functions enforce membership and moderation checks before write.
- Chat feature is gateable by feature flag.
- Message processing supports sanitization and abuse controls.

## Group driving architecture

- Group creation/join/leave and drive-state transitions are backend-authoritative.
- Realtime updates publish participant state changes.
- Live location visibility is constrained to authorized participants.
- Safety controls (block/suspend/hide) override group visibility immediately.

## Saved drives architecture

- Saved drives are created only by explicit user action.
- Saved drives are stored separately from live location sessions.
- No implicit conversion of live location stream into history.

## Kronpoäng and Kronjakt architecture

- Kronpoäng calculation and balance are backend-owned.
- Kronjakt logic and progression are backend-owned and flag-gated.
- Client receives computed state; it does not author rewards or score truth.
- Naming note: "Kronpoäng" and "Kronjakt" are intentional product terms and should remain unchanged.

## Partner and billboard architecture

- Partners are managed via admin workflows in backend.
- Digital billboards are sponsored map placements.
- All billboard placements require admin approval.
- Billboard content must be clearly marked as marketing.

## Partner insights and aggregation architecture

- Partner insights are aggregate-only analytics.
- No individual user tracking is exposed to companies.
- Aggregation logic runs server-side with privacy-preserving outputs.

## Error logging and GitHub issue integration

- Mobile/admin clients send error events to backend.
- Backend sanitizes sensitive fields and deduplicates recurring issues.
- Backend may create GitHub Issues from qualified error clusters.
- Mobile app never calls GitHub directly.

```text
App error -> API ingest -> sanitize/dedupe -> threshold/rules -> optional GitHub Issue
```

## Feature flags and remote config

Feature flags are required for:

- live location,
- chat,
- Kronjakt,
- partner statistics,
- push notifications,
- social sharing,
- external data sources.

Flags/config are backend-controlled and consumed by clients for safe rollout and kill switches.

## External data source integration

- External APIs are integrated through backend whenever secrets or caching are needed.
- Backend adapter layer normalizes and validates third-party data before exposure.
- Client direct calls are limited to non-sensitive/public integrations only when no secret/caching concern exists.

## Caching strategy

- Cache at backend boundaries for expensive or rate-limited external requests.
- Cache derived aggregate views for admin/partner insights where safe.
- Keep auth, entitlement, and moderation checks backed by authoritative server state with strict invalidation.
- Use short-lived caching for realtime-adjacent reads to balance freshness and load.

## Production Firebase hosting model

- MVP runs in **production-only Firebase** (no separate dev/staging Firebase projects).
- Local development and testing use Firebase Emulator Suite.
- Risk mitigation relies on:
  - CI/CD quality gates,
  - branch protection,
  - feature flags/kill switches,
  - Firestore backups,
  - safe, backward-compatible data migrations,
  - production-safe rollout controls (smoke checks, phased enablement, and rapid rollback paths).
- Maximum operating budget: SEK 500 per month.

## Performance-first architecture

- Mobile-first latency optimization for map, feed, and realtime interactions.
- API design prioritizes coarse-grained, low-roundtrip Cloud Function calls.
- Use targeted Firestore indexing and denormalized read models where needed.
- Apply pagination, bounded payloads, and transport compression where appropriate.

## Observability and operational controls

- Centralized backend logging, error ingestion, and audit trails for admin actions.
- Realtime health metrics (connections, fan-out, lag, disconnect rates).
- Operational controls via flags and admin tools to degrade gracefully during incidents.
- Alerting focuses on auth failures, entitlement verification failures, moderation anomalies, and integration outages.

## Security boundaries

- Firebase Authentication handles provider token verification and identity binding.
- Firebase App Check protects Cloud Functions from unauthorized callers.
- Firebase Security Rules enforce read/write access on Firestore and Realtime Database.
- Cloud Functions are the only path for admin operations and privilege escalation.
- Custom claims are set server-side only; clients cannot self-elevate.
- Data minimization for location and partner insights.
- Sanitization and deduplication in error pipelines before downstream issue creation.

## Future scalability

- Monorepo boundaries support independent scaling of mobile, admin, and backend concerns.
- Cloud Functions scale automatically with usage; no server management required.
- Realtime architecture can evolve from Firestore/Realtime Database listeners to additional Cloud Functions fan-out as load grows.
- Domain modules (chat, group driving, rewards, partner analytics) can be isolated into separate function groups when required.
- Feature flagging enables incremental rollout of new capabilities without destabilizing core MVP flows.

## Removed legacy stack

The migration decided in [ADR-001](adr/001-firebase-platform.md) is complete. On **2026-07-28** the
two legacy implementations were deleted from this repository:

| Removed        | What it was                                              | Superseded by                               |
| -------------- | -------------------------------------------------------- | ------------------------------------------- |
| `apps/mobile`  | React Native / Expo mobile app                           | `apps/android` (Kotlin / Jetpack Compose)   |
| `services/api` | Node.js + Fastify + Prisma + PostgreSQL REST API (`/v1`) | `functions/` (Cloud Functions for Firebase) |

Notes for anyone reading the current code:

- **There is no relational database.** All durable data is in Cloud Firestore; ephemeral live
  location and presence are in the Realtime Database. The PostgreSQL schema in
  [data-model.md](data-model.md) is a historical reference only.
- **There is no REST API service.** Clients call Callable Cloud Functions and read Firestore
  directly under Security Rules.
- **Provenance comments are deliberate.** Many files under `functions/`, and the `source` fields in
  `contracts/functions/functions.json`, still name `services/api` paths — for example
  `Ports services/api/src/lib/badge-catalog.ts`. These are historical references that record where a
  business rule came from and why its semantics are what they are. They are **not** live paths, and
  they should not be rewritten or removed.
- **The removed code is recoverable** from the `legacy-final` git tag, which points at the commit
  immediately before the deletion:

  ```bash
  git show legacy-final:services/api/src/lib/badge-catalog.ts
  git ls-tree -r --name-only legacy-final apps/mobile
  ```

The migration records under [docs/migration/](migration/) are kept as written; they describe the
migration as it was carried out and are not updated to reflect the post-deletion state beyond a
status banner at the top of each.
