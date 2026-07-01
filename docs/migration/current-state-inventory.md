# Current State Inventory

This document inventories every workspace and feature in the `carcommunity` repository as of the migration assessment date. It is the authoritative baseline for the [native Firebase migration plan](native-firebase-migration-plan.md).

> **Scope:** Assessment only. No code has been moved or deleted as part of this document.

---

## Workspace summary

| Workspace | Path | Technology | Role in migration |
|---|---|---|---|
| React Native mobile app | `apps/mobile` | React Native, Expo, TypeScript | **Legacy migration source — freeze new features** |
| Admin web app | `apps/admin` | Vite, React, TypeScript, react-router-dom v7 | **Keep; connect to Firebase** |
| Firebase Cloud Functions | `apps/functions` | Node.js 22, TypeScript, firebase-functions v2 | **Target backend — expand** |
| Fastify/Prisma API | `services/api` | Node.js, Fastify, Prisma, PostgreSQL | **Legacy migration source — freeze new features** |
| Shared TypeScript contracts | `packages/shared` | TypeScript | **Reference; evolve to language-neutral contracts** |
| Firebase configuration | `firebase/` | Firebase CLI JSON, Security Rules | **Keep; authoritative Firebase config** |

---

## Package manager situation

| File | Location | Used by |
|---|---|---|
| `package-lock.json` | root | npm workspaces (mobile, admin, api, shared, functions) |
| `pnpm-lock.yaml` | root | pnpm (`apps/functions` only via `pnpm-workspace.yaml`) |

**Issue:** Two lock files (npm and pnpm) coexist. `apps/functions` is listed in both the npm workspace (`package.json` `workspaces`) and the `pnpm-workspace.yaml`. This is a hygiene issue; the dual-manager situation should be resolved. Recommended fix: standardize on npm for all workspaces once the functions workspace stabilizes (see migration plan Phase 3).

---

## Repository hygiene issues

| Issue | Location | Recommended action |
|---|---|---|
| Docker build for legacy API | `services/api/Dockerfile`, CI `container-build` job | Mark as legacy; remove after API cutover |
| `pnpm-lock.yaml` alongside `package-lock.json` | root | Resolve: standardize on npm once functions workspace is stable |
| Azure references in `docs/security.md` | `docs/security.md` | Correct to Firebase |
| Legacy architecture in `README.md` | `README.md` | Correct to target architecture |
| Legacy architecture in `docs/product-decisions.md` | `docs/product-decisions.md` | Update; remove Azure/PostgreSQL as target |
| Prisma Dependabot ignore entries for v7 | `.github/dependabot.yml` | Remove after services/api is retired |
| Mobile Dependabot entries for Expo/React Native | `.github/dependabot.yml` | Remove after apps/mobile is retired |
| `pnpm-workspace.yaml` `allowBuilds` stub entries | `pnpm-workspace.yaml` | Complete or remove placeholder values |
| `apps/functions` in npm `workspaces` array | `package.json` | Clarify: functions uses pnpm; npm workspace entry is redundant |
| Container build CI job | `.github/workflows/ci.yml` `container-build` job | Remove after services/api cutover |
| No `contracts/` directory | root | Create as part of Phase 2 |
| `apps/mobile` Dependabot entries | `.github/dependabot.yml` | Remove after apps/mobile is retired |

---

## `apps/mobile` — React Native / Expo application

**Path:** `apps/mobile/`  
**Technology:** React Native, Expo, TypeScript, Jest  
**Status:** Feature-complete MVP. **Frozen: no new product features.**  
**Tests:** Jest with `jest-expo`; test files under `src/screens/__tests__/`, `src/hooks/__tests__/`  
**Dependencies:** Expo SDK, react-navigation, Mapbox (`@rnmapbox/maps`), react-native-gesture-handler, react-native-reanimated

### Code categories

| Directory/file | Category | Notes |
|---|---|---|
| `src/api/` | React Native-specific | HTTP client wrappers against `services/api`; will be replaced by Firebase SDK calls |
| `src/screens/` | React Native-specific | All UI screens; replaced by SwiftUI/Compose |
| `src/hooks/` | React Native-specific | React hooks; business logic is reusable as reference |
| `src/components/` | React Native-specific | Shared UI components; replaced by native UI |
| `src/navigation/` | React Native-specific | react-navigation stack; replaced by native navigation |
| `src/i18n/en.json`, `sv.json` | **Reusable** | Swedish and English localization strings; source of truth for native apps |
| `src/featureFlags/index.ts` | **Reusable as reference** | Feature flag key names; move to contracts |
| `src/config/` | **Reusable as reference** | App and brand configuration shapes |
| `src/design/` | **Reusable as reference** | Design token definitions; move to contracts |
| `src/context/` | React Native-specific | React Context providers; replaced by native state management |
| `src/session/` | React Native-specific | Custom session/token management; replaced by Firebase Auth SDK |
| `src/storage/` | React Native-specific | AsyncStorage wrappers; replaced by Keychain (iOS) / Keystore (Android) |
| `src/utils/` | **Reusable as reference** | Utility functions; evaluate for native port |
| `app.config.ts` | Expo-specific | Expo config; not used in native targets |
| `metro.config.js` | Expo-specific | Metro bundler config; not used in native targets |
| `babel.config.js` | Expo-specific | Not used in native targets |
| `App.tsx` | React Native-specific | Root app entry; replaced by SwiftUI App struct / Android Application class |

### Feature implementation status (mobile)

| Feature | Screen(s) / Hook(s) | API module | Status |
|---|---|---|---|
| Authentication | `LoginScreen.tsx` | `api/auth.ts` | Implemented; uses custom token session |
| Onboarding | `OnboardingScreen.tsx` | `api/profile.ts` | Implemented |
| Profile and privacy settings | `ProfileScreen.tsx`, `PrivacySettingsScreen.tsx` | `api/profile.ts` | Implemented |
| Notifications settings | `NotificationSettingsScreen.tsx` | `api/notifications.ts` | Implemented |
| Notifications list | `NotificationsScreen.tsx` | `api/notifications.ts` | Implemented |
| Live location session | `LiveLocationScreen.tsx` | `api/live-location.ts` | Implemented; hooks in `useLiveLocationSession.ts` |
| Map + live markers | `MapScreen.tsx` | `api/live-location.ts` | Implemented; Mapbox RN SDK |
| Blocking | `BlockedUsersScreen.tsx` | `api/blocking.ts` | Implemented |
| Events list + detail | `EventsScreen.tsx`, `EventDetailScreen.tsx` | `api/events.ts` | Implemented |
| RSVP | `EventDetailScreen.tsx` | `api/events.ts` | Implemented |
| Event chat | `EventChatScreen.tsx` | `api/event-chat.ts` | Implemented; `useEventChat.ts` |
| Group driving | `GroupDriveScreen.tsx` | `api/group-drive.ts` | Implemented; `useGroupDrive.ts`, `useGroupDriveMarkers.ts` |
| Saved drives list + detail | `SavedDrivesScreen.tsx`, `SavedDriveDetailScreen.tsx` | `api/saved-drives.ts` | Implemented |
| Garage / vehicles | `GarageScreen.tsx`, `VehicleDetailScreen.tsx`, `VehicleFormScreen.tsx` | `api/garage.ts` | Implemented |
| Badges | `BadgesScreen.tsx` | `api/badges.ts` | Implemented; `useBadges.ts` |
| Kronpoäng (points wallet) | `PointsWalletScreen.tsx` | `api/points.ts` | Implemented; `usePoints.ts` |
| Kronjakt (crown hunt) | `CrownHuntScreen.tsx` | `api/crown-hunt.ts` | Implemented; `useCrownHunt.ts` |
| Partners list + detail | `PartnerDetailScreen.tsx` | `api/partners.ts` | Implemented; map markers via `usePartnerMarkers.ts` |
| Partner offers | (within PartnerDetailScreen) | `api/partner-offers.ts` | Implemented |
| Partner application | `PartnerApplicationScreen.tsx` | — | Screen exists (Implemented) |
| Partner insights opt-in | (within settings) | `api/partner-insights.ts` | Implemented |
| Digital billboards | `BillboardDetailScreen.tsx` | `api/digital-billboards.ts` | Implemented; `useBillboardMarkers.ts` |
| Subscription | (within settings) | `api/subscription.ts` | Implemented (client-side only; platform billing not wired) |
| Diagnostics/crash reports | — | `api/diagnostics.ts` | API module exists; no dedicated screen |
| Chat (general) | `ChatScreen.tsx` | — | Screen exists; unclear if wired to a backend domain |
| Home screen | `HomeScreen.tsx` | — | Implemented |
| Settings | `SettingsScreen.tsx` | — | Implemented |
| About App | `AboutAppScreen.tsx` | — | Implemented |
| Suspended account | `SuspendedAccountScreen.tsx` | — | Implemented |

### Code that will be replaced

All React Native-specific code (screens, hooks, navigation, API clients, session management, storage wrappers, Expo config) will be replaced by native SwiftUI and Kotlin/Compose implementations.

### Code that may remain (as reference)

- `src/i18n/en.json` and `sv.json` — source strings for native localization
- `src/featureFlags/index.ts` — feature flag key names
- `src/design/` — design token definitions
- `src/utils/` — pure utility logic to review for native port

---

## `services/api` — Fastify/Prisma backend

**Path:** `services/api/`  
**Technology:** Node.js, Fastify, Prisma 6, PostgreSQL  
**Status:** Feature-complete MVP. **Frozen: no new product features.**  
**Tests:** Vitest; test files are co-located under `src/*.test.ts`  
**Dependencies:** Fastify, Prisma, pg, zod, firebase-admin (for token verification)

### Code categories

| Module | Category | Notes |
|---|---|---|
| `src/routes/` | Fastify-specific | REST route handlers; replaced by callable Cloud Functions |
| `src/lib/` | **Reusable business logic** | Service classes contain authoritative business rules; port to Cloud Functions |
| `src/plugins/` | Fastify-specific | Fastify plugin setup; replaced by Functions middleware |
| `src/lib/firebase-admin.ts` | Firebase-ready | Already uses Firebase Admin SDK for token verification |
| `src/lib/firebase-id-token-verifier.ts` | Firebase-ready | Firebase Auth token verification; reusable pattern |
| `src/lib/auth-context.ts` | **Reusable as reference** | Auth context with role/status/entitlement checks |
| `src/lib/errors.ts` | **Reusable** | AppError class and error codes; port to Functions |
| `src/lib/brand-config.ts` | **Reusable** | Brand configuration; port to shared contracts |
| `src/config.ts` | **Reusable as reference** | Environment/feature flag defaults |
| `prisma/schema.prisma` | Prisma/PostgreSQL-specific | Authoritative data model reference for Firestore migration |
| `prisma/migrations/` | PostgreSQL-specific | Migration history; reference only |
| `Dockerfile` | Azure/Docker-specific | Not used in Firebase target |
| `scripts/` | PostgreSQL-specific | Database scripts; not used in Firebase target |

### Service classes (business logic to port)

| Service | Path | Domain |
|---|---|---|
| `auth-service.ts` | `src/lib/` | Authentication, onboarding |
| `user-service.ts` | `src/lib/` | User profiles, privacy |
| `live-location-service.ts` | `src/lib/` | Live location sessions, positions |
| `event-service.ts` | `src/lib/` | Events, RSVP |
| `event-chat-service.ts` | `src/lib/` | Event chat, moderation |
| `group-drive-service.ts` | `src/lib/` | Group driving |
| `saved-drive-service.ts` | `src/lib/` | Saved drives |
| `garage-service.ts` | `src/lib/` | Vehicles/garage |
| `badge-service.ts` | `src/lib/` | Badges, gamification |
| `points-service.ts` | `src/lib/` | Kronpoäng ledger |
| `crown-hunt-service.ts` | `src/lib/` | Kronjakt, claim validation |
| `crown-hunt-geo.ts` | `src/lib/` | Geofence logic for Kronjakt |
| `crown-hunt-risk.ts` | `src/lib/` | Anti-fraud risk scoring |
| `blocking-service.ts` | `src/lib/` | User blocking |
| `moderation-service.ts` | `src/lib/` | Moderation actions, suspension |
| `subscription-service.ts` | `src/lib/` | Subscription entitlement |
| `notification-service.ts` | `src/lib/` | Push notification delivery |
| `notification-delivery-service.ts` | `src/lib/` | Notification delivery logic |
| `partner-application-service.ts` | `src/lib/` | Partner applications |
| `partner-company-service.ts` | `src/lib/` | Partner company management |
| `partner-offer-service.ts` | `src/lib/` | Partner offers |
| `partner-insights-service.ts` | `src/lib/` | Partner analytics (aggregated) |
| `billboard-service.ts` | `src/lib/` | Digital billboards |
| `diagnostics-service.ts` | `src/lib/` | Crash/error reports |
| `drive-calculations.ts` | `src/lib/` | Drive metric calculations |
| `badge-catalog.ts` | `src/lib/` | Badge definitions |

### Prisma models (data migration targets)

| Prisma model | Firebase target |
|---|---|
| `User` | `users/` (Firestore) + Firebase Auth custom claims |
| `UserIdentity` | Firebase Authentication (provider subjects handled by Auth) |
| `Session` | Firebase Authentication ID tokens (no custom sessions) |
| `Organization` | `config/organizations/` (Firestore) or app-level config |
| `OrganizationMember` | Firebase custom claims + `users/` role field |
| `FeatureFlag` | `config/featureFlags/` (Firestore) |
| `LiveLocationSession` | `liveLocationSessions/` (Realtime Database) |
| `LiveLocationLatestPosition` | `liveLocation/{uid}/latest` (Realtime Database) |
| `ModerationAction` | `moderationActions/` (Firestore) |
| `AuditLog` | `auditLogs/` (Firestore) |
| `DiagnosticsReport` | `diagnosticsReports/` (Firestore) |
| `Event` | `events/` (Firestore) |
| `EventRsvp` | `events/{eventId}/rsvps/` (Firestore subcollection) |
| `EventGroupDriveParticipant` | `events/{eventId}/groupDriveParticipants/` (Firestore subcollection) |
| `EventChatMessage` | `events/{eventId}/messages/` (Firestore subcollection) |
| `EventChatMessageReport` | `events/{eventId}/messageReports/` (Firestore subcollection) |
| `UserBlock` | `userBlocks/{uid}/blocked/` (Firestore subcollection) |
| `SubscriptionRecord` | `subscriptions/{uid}` (Firestore) |
| `SavedDrive` | `rides/` (Firestore) + Cloud Storage for route data |
| `Vehicle` | `vehicles/` (Firestore) |
| `UserBadge` | `users/{uid}/badges/` (Firestore subcollection) |
| `PointsLedgerEntry` | `pointsLedger/{uid}/entries/` (Firestore subcollection) |
| `CrownHuntPoint` | `crownHuntPoints/` (Firestore) |
| `CrownHuntClaim` | `crownHuntClaims/` (Firestore) |
| `PartnerApplication` | `partnerApplications/` (Firestore) |
| `PartnerCompany` | `companies/` (Firestore) |
| `PartnerOffer` | `offers/` (Firestore) |
| `PartnerInsightsEvent` | `partnerInsightsEvents/` (Firestore, short TTL) |
| `SponsoredBillboard` | `billboards/` (Firestore) |
| `PushDeviceRegistration` | `userPrivate/{uid}/pushTokens/` (Firestore subcollection) |
| `NotificationPreference` | `userPrivate/{uid}` notification preferences field |
| `UserNotification` | `notifications/{uid}/items/` (Firestore subcollection) |

### Fastify-specific code to replace

- `src/routes/` — all route handlers
- `src/plugins/` — Fastify plugin wiring
- `src/server.ts` — Fastify server setup
- `Dockerfile` — container packaging
- `prisma/` — ORM and migrations

### Code that may remain (as reference)

- `src/lib/` service classes — authoritative business rule implementations to port
- `src/lib/errors.ts` — error codes to standardize in `contracts/errors`
- `src/lib/auth-context.ts` — auth context pattern to replicate in Cloud Functions
- `src/lib/firebase-admin.ts`, `firebase-id-token-verifier.ts` — Firebase token verification already in place

---

## `apps/admin` — Admin web application

**Path:** `apps/admin/`  
**Technology:** Vite 7, React, TypeScript, react-router-dom v7, Vitest  
**Status:** Feature-rich; Firebase Authentication integration incomplete.  
**Tests:** Vitest; test files under `src/features/*/`  
**Dependencies:** React, react-router-dom, Vite

### Feature areas (admin)

| Feature area | Path | Status |
|---|---|---|
| Authentication / login | `src/app/login/`, `src/features/auth/` | Implemented; needs Firebase Auth connection |
| Users list + detail | `src/app/users/` | Implemented |
| Moderation reports | `src/app/moderation-reports/` | Implemented |
| Event administration | `src/app/events/` | Implemented |
| Event chat moderation | `src/app/event-chat/` | Implemented |
| Partner management | `src/app/partners/` | Implemented |
| Partner offers | (within partners) | Implemented |
| Partner insights | (within partners or standalone) | Implemented via `src/features/partner-insights/` |
| Digital billboards | `src/app/billboards/` | Implemented |
| Kronjakt administration | `src/app/kronjakt/` | Implemented |
| Badges administration | `src/app/badges/` | Implemented |
| Points (Kronpoäng) admin | (within users) | Implemented via `src/app/users/[id]/PointsSection.tsx` |
| Feature flags | `src/app/feature-flags/` | Implemented |
| Notifications | `src/app/notifications/` | Implemented |
| Diagnostics / error reports | `src/app/error-reports/` | Implemented |
| Announcements | `src/app/announcements/` | Implemented |
| Audit log | `src/app/audit-log/` | Implemented |
| Account deletions | `src/app/account-deletions/` | Implemented |
| Support | `src/app/support/` | Implemented |
| Settings | `src/app/settings/` | Implemented |
| Reports | `src/app/reports/` | Implemented |
| Live location monitoring | `src/app/live-location/` | Implemented |

### Code that will remain with changes

All admin web code will remain but must be migrated from the Fastify API to callable Cloud Functions, and authentication must switch from the custom session system to Firebase Authentication + custom claims.

### Code that will be replaced

- Custom HTTP API client (`src/lib/` API wrappers that call `services/api`)
- Custom session management
- Any hardcoded API base URL references

---

## `apps/functions` — Cloud Functions (Firebase backend)

**Path:** `apps/functions/`  
**Technology:** Node.js 22, TypeScript, firebase-functions v2, Firebase Admin SDK  
**Status:** **Scaffold only.** Single `health` function implemented.  
**Tests:** Vitest for unit tests; emulator tests in `src/__tests__/`  
**Configuration:** `firebase/firebase.json` — functions source points to `../apps/functions`

### Current functions

| Function | Type | Description |
|---|---|---|
| `health` | HTTP | Liveness check. Returns `{ status: "ok" }`. |

### Emulator configuration

- Auth: port 9099
- Functions: port 5001
- Firestore: port 8080
- Realtime Database: port 9000
- Storage: port 9199
- Hosting: port 5000
- UI: port 4000

### Security rules

| File | Status |
|---|---|
| `firebase/firestore.rules` | Exists; needs full domain rules |
| `firebase/database.rules.json` | Exists; needs full domain rules |
| `firebase/storage.rules` | Exists; needs full domain rules |
| `firebase/firestore.indexes.json` | Exists; needs domain indexes |

### Code that will expand

All domain-specific callable functions, triggers, and scheduled functions will be added here as the migration progresses.

---

## `packages/shared` — TypeScript contracts

**Path:** `packages/shared/`  
**Technology:** TypeScript  
**Status:** Complete TypeScript contracts for all domains.

### Contract modules

| Module | Domain |
|---|---|
| `auth.ts` | Authentication, session |
| `users.ts` | User profiles |
| `live-location.ts` | Live location sessions and positions |
| `events.ts` | Events and RSVP |
| `event-chat.ts` | Event chat messages |
| `group-drive.ts` | Group driving |
| `saved-drives.ts` | Saved drive records |
| `garage.ts` | Vehicles |
| `badges.ts` | Badges |
| `points.ts` | Kronpoäng (points) |
| `crown-hunt.ts` | Kronjakt |
| `blocking.ts` | User blocking |
| `notifications.ts` | Push notifications and preferences |
| `onboarding.ts` | Onboarding flow |
| `subscription.ts` | Subscription entitlement |
| `partners.ts` | Partner companies |
| `partner-offers.ts` | Partner offers |
| `partner-insights.ts` | Partner analytics |
| `digital-billboards.ts` | Digital billboards |
| `diagnostics.ts` | Diagnostics reports |
| `feature-flags.ts` | Feature flag keys |
| `moderation.ts` | Moderation actions |

### Limitation

These TypeScript contracts **cannot be imported as executable code** by native Swift/Kotlin apps. They must be translated to a language-neutral format (JSON Schema or similar) in `contracts/` to serve as the cross-platform source of truth.

### Code that will remain with changes

The TypeScript contracts will remain as the backend/admin contract implementation. A parallel language-neutral `contracts/` directory will be created to serve native apps.

---

## `firebase/` — Firebase configuration

| File | Status |
|---|---|
| `firebase.json` | Complete; emulator config, hosting config, rules references |
| `firestore.rules` | Exists; needs expansion |
| `firestore.indexes.json` | Exists; needs domain indexes |
| `database.rules.json` | Exists; needs domain rules |
| `storage.rules` | Exists; needs domain rules |
| `.firebaserc` | Exists; project aliases configured |

---

## Feature completion summary

The table below gives a high-level completion assessment across the three platforms.

| Feature domain | `apps/mobile` (RN) | `services/api` | `apps/admin` | `apps/functions` (Firebase) | iOS native | Android native |
|---|---|---|---|---|---|---|
| Authentication | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Onboarding | ✅ | ✅ | — | 🔲 | 🔲 | 🔲 |
| User profile | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Privacy settings | ✅ | ✅ | — | 🔲 | 🔲 | 🔲 |
| Roles and access | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Subscription / entitlement | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Feature flags | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Live location | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Map and live markers | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Blocking | ✅ | ✅ | — | 🔲 | 🔲 | 🔲 |
| Events | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| RSVP | ✅ | ✅ | — | 🔲 | 🔲 | 🔲 |
| Event chat | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Group driving | ✅ | ✅ | — | 🔲 | 🔲 | 🔲 |
| Saved drives | ✅ | ✅ | — | 🔲 | 🔲 | 🔲 |
| Garage / vehicles | ✅ | ✅ | — | 🔲 | 🔲 | 🔲 |
| Badges | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Kronpoäng | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Kronjakt | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Partners | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Partner offers | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Partner application | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Partner insights | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Digital billboards | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Notifications | ✅ | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Diagnostics | Partial | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Moderation | — | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Audit logs | — | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Account deletion | Partial | ✅ | ✅ | 🔲 | 🔲 | 🔲 |
| Firebase backend | — | — | — | 🟡 scaffold | — | — |

**Legend:** ✅ Implemented · 🟡 Partial / scaffold · 🔲 Not started · — Not applicable

---

## Security-sensitive behavior summary

The following behavior must be preserved exactly during migration:

1. **Admin role**: verified from Firebase custom claims only; clients never self-elevate.
2. **Subscription entitlement** (`member_monthly`): backend-verified; client state is advisory.
3. **Live location visibility**: entitlement-gated; only active members see others' positions.
4. **Blocking**: filters live location, chat, group driving, and interaction visibility.
5. **Suspension**: overrides subscription and normal feature access; suspended users retain access to support, account deletion, and terms only.
6. **Kronjakt claims**: backend validates geofence, speed ≤ `KRONJAKT_MIN_SPEED_KMH`, stationary ≥ `KRONJAKT_MIN_STATIONARY_SECONDS`, active session, cooldown, and risk score.
7. **Partner statistics**: opt-in only; aggregated; minimum 10 unique users before reporting.
8. **Saved drives**: only stored after explicit user action; no silent auto-save.
9. **Live location TTL**: maximum `LIVE_LOCATION_TTL_MINUTES_MAX` (15 minutes); "Hide me now" removes immediately.
10. **Sensitive token storage**: tokens must use Keychain (iOS) and Android Keystore (Android); never plain storage.

---

## Existing CI workflows

| Workflow file | What it validates | Legacy dependency |
|---|---|---|
| `ci.yml` | All workspaces: lint, typecheck, test, build; container build | ✅ Docker build is legacy |
| `validate-functions.yml` | Functions: lint, typecheck, unit tests, build | None |
| `test-firebase-rules.yml` | Firebase emulator: Firestore, RTDB, Storage rules | None |
| `validate-admin-web.yml` | Admin web: lint, typecheck, tests, build | None |
| `codeql.yml` | CodeQL security analysis | None |
| `dependency-review.yml` | Dependency vulnerability review | None |
| `deploy-firebase-functions.yml` | Cloud Functions deployment | None |
| `deploy-firebase-hosting.yml` | Firebase Hosting deployment | None |

CI workflows needed but not yet present:
- iOS build and test
- Android build and test (Gradle)
