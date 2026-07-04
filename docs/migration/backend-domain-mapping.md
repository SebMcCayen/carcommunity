# Backend Domain Mapping

This document maps every concept in the existing `services/api` (Fastify, Prisma, PostgreSQL) backend to its equivalent in the target Firebase architecture.

See [ADR-001](../adr/001-firebase-platform.md) for the platform decision and [firebase-data-model.md](../firebase-data-model.md) for Firestore collection field definitions.

---

## Custom sessions → Firebase Authentication ID tokens

| Aspect             | Legacy (`services/api`)                                             | Target (Firebase)                                                       |
| ------------------ | ------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Session concept    | Custom `Session` Prisma model with hashed token, expiry, revocation | Firebase ID token (JWT, short-lived, auto-refreshed)                    |
| Token storage      | `session_tokens` table (hashed)                                     | Firebase SDK manages token lifecycle; never stored in Firestore         |
| Token verification | `firebase-id-token-verifier.ts` (already calls Firebase Admin SDK)  | Firebase Admin SDK `auth.verifyIdToken()` in every callable function    |
| Token revocation   | `revokedAt` field on Session record                                 | Firebase `auth.revokeRefreshTokens(uid)`                                |
| Identity key       | Internal UUID `users.id`                                            | Firebase UID (`uid`) — stable, provider-independent                     |
| Provider binding   | `UserIdentity` table (apple/google provider + subject)              | Firebase Authentication handles provider binding                        |
| Provider subject   | `user_identities.provider_subject`                                  | Firebase UID is the canonical identity; raw subject managed by Firebase |

**Migration risk:** Low. `services/api` already verifies Firebase ID tokens for Firebase-authenticated requests. The custom session system is additive legacy; removing it requires no data migration since sessions are short-lived.

**Path design:** No Firestore path for sessions. Firebase Auth SDK handles token lifecycle on all clients.

---

## User roles → Firebase custom claims + authoritative Firestore user state

| Aspect             | Legacy                                                    | Target                                                                                                       |
| ------------------ | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Role storage       | `users.role` PostgreSQL enum (`user`, `admin`, `owner`)   | Firebase custom claim `admin: true` set by `setAdminRole` callable function                                  |
| Role check in API  | `requireAdminHook` reads role from Prisma session context | Callable function middleware reads `decodedToken.admin` claim                                                |
| Role promotion     | Admin sets role via API endpoint                          | Admin-only `setAdminRole` callable function; no client self-elevation                                        |
| Role persistence   | PostgreSQL                                                | Firestore `users/{uid}.role` field (read-only; set by Admin SDK only)                                        |
| Role refresh       | Immediately reflected on next request                     | Custom claims propagate on next token refresh (≤1 hour); force refresh for immediate effect                  |
| Organization roles | `OrganizationMember.role` enum                            | Firestore `users/{uid}.orgRole` or `config/organizations/{orgId}/members/{uid}` — evaluate at implementation |

**Collection/path design:**

- `users/{uid}` — public profile document with `role` field (backend-managed only, blocked for client writes in Security Rules)
- Custom claim: `{ admin: true }` (set via Firebase Admin SDK in `setAdminRole` callable)

**Index:** `users` collection — no composite index required for admin lookup (direct UID access).

**Transaction requirements:** None; admin promotion is a single atomic write (custom claim + Firestore field).

**Security-rule requirements:** `users/{uid}.role` must be listed as an immutable field in Firestore Security Rules — clients must not be able to write it.

**Cost consideration:** Custom claim reads are free (part of ID token); no extra Firestore reads for role checks.

**Migration risk:** Medium. Admin users must be migrated to Firebase Authentication first (Phase 7). Existing admin UIDs from PostgreSQL must be mapped to Firebase UIDs before role promotion.

---

## Prisma user data → Firestore user documents

| Prisma field                       | Firestore path                                            | Notes                                 |
| ---------------------------------- | --------------------------------------------------------- | ------------------------------------- |
| `users.id` (UUID)                  | Firebase UID (replaces UUID)                              | Firebase UID is the new canonical key |
| `users.displayName`                | `users/{uid}.displayName`                                 | Public                                |
| `users.email`                      | `userPrivate/{uid}.email`                                 | Private; not identity key             |
| `users.role`                       | `users/{uid}.role` + custom claim                         | Backend-managed only                  |
| `users.status`                     | `users/{uid}.suspended` (boolean) + `users/{uid}.deleted` | Simplified from enum to boolean flags |
| `users.subscriptionEntitlement`    | `users/{uid}.activeMember` (boolean) + custom claim       | Backend-managed only                  |
| `users.onboardingCompletedAt`      | `users/{uid}.onboardingCompletedAt`                       | Timestamp                             |
| `users.ageConfirmedAt`             | `userPrivate/{uid}.ageConfirmedAt`                        | Private                               |
| `users.termsAcceptedAt`            | `userPrivate/{uid}.termsAcceptedAt`                       | Private                               |
| `users.privacyPolicyAcceptedAt`    | `userPrivate/{uid}.privacyPolicyAcceptedAt`               | Private                               |
| `users.anonymousPartnerStatsOptIn` | `userPrivate/{uid}.anonymousPartnerStatsOptIn`            | Private; default false                |
| `users.lastActiveAt`               | `users/{uid}.lastActiveAt`                                | Semi-public                           |
| `users.firebaseUid`                | Firebase UID directly                                     | No mapping needed                     |
| `users.createdAt`                  | `users/{uid}.createdAt`                                   | Server timestamp                      |
| `users.deletedAt`                  | `users/{uid}.deleted` (boolean)                           | Soft-delete becomes boolean flag      |

**Read patterns:** User profile read by UID (point read, O(1), no index needed). User list for admin uses `users` collection with pagination (`startAfter`, `limit`).

**Write patterns:** Profile update by owner (non-protected fields only). Role/status/entitlement update by Admin SDK only.

**Security-rule requirements:** Protected fields (`role`, `activeMember`, `suspended`, `deleted`) — `request.resource.data.keys().hasAny(['role','activeMember','suspended','deleted'])` must deny for any client write.

**Retention/TTL:** No automatic expiry. Account deletion: soft-delete `deleted: true` immediately; hard-delete after retention window (implementation-defined).

**Cost consideration:** Point reads on `users/{uid}` are inexpensive. Avoid full collection scans; use paginated queries for admin views.

---

## Prisma relational models → Firestore collections and subcollections

### Design principles

- Prefer subcollections for one-to-many relationships where both parent and child are accessed together.
- Prefer separate top-level collections for many-to-many or cross-entity queries.
- Denormalize display names and other frequently read fields to avoid extra Firestore reads.
- Route data (GPS point arrays) goes to Cloud Storage, never Firestore.

### Collection design

| Domain                         | Firestore path                                  | Notes                                                                        |
| ------------------------------ | ----------------------------------------------- | ---------------------------------------------------------------------------- |
| User profile                   | `users/{uid}`                                   | Public profile. Document ID = Firebase UID                                   |
| User private data              | `userPrivate/{uid}`                             | Owner-only. Email, phone, preferences, consent timestamps                    |
| Vehicles (garage)              | `vehicles/{vehicleId}`                          | Top-level; filtered by `userId` field. Paginated by `userId, createdAt DESC` |
| Events                         | `events/{eventId}`                              | Top-level; composite index: `status ASC, startsAt ASC`                       |
| Event RSVPs                    | `events/{eventId}/rsvps/{uid}`                  | Subcollection; document ID = Firebase UID of respondent                      |
| Event group drive participants | `events/{eventId}/groupDriveParticipants/{uid}` | Subcollection; document ID = Firebase UID                                    |
| Event chat messages            | `events/{eventId}/messages/{messageId}`         | Subcollection; paginated by `createdAt DESC`                                 |
| Event chat message reports     | `events/{eventId}/messageReports/{reportId}`    | Subcollection                                                                |
| User blocks                    | `userBlocks/{uid}/blocked/{targetUid}`          | Subcollection; document ID = blocked Firebase UID                            |
| Subscription records           | `subscriptions/{uid}`                           | Single document per user (latest active subscription)                        |
| Saved drives                   | `rides/{rideId}`                                | Top-level; filtered by `userId`; route data in Cloud Storage                 |
| User badges                    | `users/{uid}/badges/{badgeId}`                  | Subcollection; badge definitions denormalized                                |
| Points ledger                  | `pointsLedger/{uid}/entries/{entryId}`          | Subcollection; balance denormalized to `pointsLedger/{uid}.balance`          |
| Crown hunt points              | `crownHuntPoints/{pointId}`                     | Top-level; admin-managed geographic points                                   |
| Crown hunt claims              | `crownHuntClaims/{claimId}`                     | Top-level; created by `submitCrownHuntClaim` callable                        |
| Partner companies              | `companies/{companyId}`                         | Top-level; admin-managed                                                     |
| Partner offers                 | `offers/{offerId}`                              | Top-level; filtered by `companyId`                                           |
| Saved partner offers           | `users/{uid}/savedOffers/{offerId}`             | Subcollection                                                                |
| Partner applications           | `partnerApplications/{applicationId}`           | Top-level; admin-reviewed                                                    |
| Partner insights events (raw)  | `partnerInsightsEvents/{eventId}`               | Top-level; 7-day TTL; scheduled cleanup                                      |
| Partner insights (aggregated)  | `partnerInsights/{period}/{companyId}`          | Top-level; aggregated by scheduled function                                  |
| Sponsored billboards           | `billboards/{billboardId}`                      | Top-level; admin-approved                                                    |
| Push tokens                    | `userPrivate/{uid}/pushTokens/{tokenId}`        | Subcollection; encrypted token                                               |
| In-app notifications           | `notifications/{uid}/items/{notificationId}`    | Subcollection; paginated by `createdAt DESC`                                 |
| Feature flags                  | `config/featureFlags`                           | One flat document; boolean field per flag key                                |
| Moderation actions             | `moderationActions/{actionId}`                  | Top-level; admin-only write; immutable records                               |
| Audit logs                     | `auditLogs/{logId}`                             | Top-level; admin-only write; immutable records                               |
| Diagnostics reports            | `diagnosticsReports/{reportId}`                 | Top-level; admin-only read                                                   |
| Organization config            | `config/organizations/{orgId}`                  | Single organization for MVP                                                  |

---

## Live location latest position → Realtime Database

| Aspect      | Legacy (`services/api`)                                                                                 | Target (Firebase Realtime Database)                          |
| ----------- | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| Storage     | `live_location_latest_positions` (PostgreSQL table)                                                     | `liveLocation/{uid}/latest` (RTDB node)                      |
| Fields      | `latitude`, `longitude`, `accuracy_meters`, `heading_degrees`, `speed_meters_per_second`, `recorded_at` | Same fields, snake_case to camelCase                         |
| Write       | `PATCH /v1/live/sessions/location` REST endpoint                                                        | `updateLivePosition` callable function                       |
| Read        | `GET /v1/live/visible-users` REST endpoint                                                              | RTDB snapshot listener (entitlement-gated)                   |
| TTL         | `expiresAt` field; scheduled cleanup                                                                    | RTDB `onDisconnect` clear + scheduled Cloud Function cleanup |
| Hide me now | `DELETE` latest position row                                                                            | RTDB `liveLocation/{uid}/latest` node delete (Admin SDK)     |

**Path design:**

```
liveLocation/
  {uid}/
    session/         # Active session state (started, expiresAt, status)
    latest/          # Latest position snapshot
      latitude
      longitude
      accuracyMeters
      headingDegrees
      speedMetersPerSecond
      recordedAt     # ISO-8601 timestamp string
    presence/        # Connected / disconnected state
```

**Expected read patterns:** Authenticated members read `liveLocation/*/latest` via a filtered RTDB query (on `visible: true` field or similar). Blocking filter applied server-side in callable function or Security Rules.

**Expected write patterns:** User's own `liveLocation/{uid}/latest` — owner-write only; frequent updates (every few seconds while sharing).

**Transaction requirements:** None for position updates (single-node overwrite). Session start/stop uses callable function to update both RTDB session node and custom claim if needed.

**Security-rule requirements:**

- `liveLocation/{uid}/session` — deny all client writes (callable function / Admin SDK only); `activeMember` claim required to read others.
- `liveLocation/{uid}/latest` — owner write; `activeMember` claim + no blocking required to read others.
- `liveLocation/{uid}/presence` — owner write only.

**Retention/TTL:** 15-minute maximum. `onDisconnect` clears `latest` on disconnect. Scheduled function runs every 5 minutes to purge expired sessions and their `latest` positions.

**Cost consideration:** RTDB pricing is per GB downloaded and per GB stored. Keep `latest` node lean (8 numeric fields). Use `limitToLast(100)` or similar to bound live user queries. Disconnect cleanup prevents stale data accumulation.

**Migration risk:** Low. No live-location data needs to be migrated from PostgreSQL (ephemeral by nature; all sessions expire). The RTDB can start empty.

---

## Active presence → Realtime Database

| Path             | Purpose                                          |
| ---------------- | ------------------------------------------------ |
| `presence/{uid}` | Online/offline indicator; set via `onDisconnect` |

**Write pattern:** Client sets `presence/{uid}` to `{ online: true, lastSeen: timestamp }` on connect; `onDisconnect` sets `{ online: false, lastSeen: timestamp }`.

**Cost consideration:** Very cheap; small node, low write volume.

---

## Saved drives → Firestore + Cloud Storage

| Aspect            | Legacy                                      | Target                                               |
| ----------------- | ------------------------------------------- | ---------------------------------------------------- |
| Drive metadata    | `saved_drives` table (Prisma)               | `rides/{rideId}` Firestore document                  |
| Route GPS data    | Not stored (drive only has summary stats)   | Cloud Storage: `rideRoutes/{uid}/{rideId}/route.bin` |
| Distance/duration | Computed by `drive-calculations.ts` service | Same logic ported to `saveDrive` callable function   |
| Ownership         | `userId` UUID foreign key                   | `userId` = Firebase UID                              |

**Firestore document:** `rides/{rideId}` — `userId`, `title`, `distanceMeters`, `durationSeconds`, `startedAt`, `endedAt`, `routePath` (Cloud Storage path), `previewImagePath`, `createdAt`.

**Indexes:** `userId ASC, createdAt DESC` (user's ride history list, paginated).

**Security-rule requirements:** Owner-only read/write/delete. Route file in Cloud Storage: owner-only access.

**Retention/TTL:** No automatic expiry. User can delete at any time.

**Migration risk:** Low. No production drive data in PostgreSQL to migrate.

---

## Event and RSVP data → Firestore

| Aspect                   | Legacy                                 | Target                                                        |
| ------------------------ | -------------------------------------- | ------------------------------------------------------------- |
| Event                    | `events` table                         | `events/{eventId}` Firestore document                         |
| RSVP                     | `event_rsvps` table                    | `events/{eventId}/rsvps/{uid}` subcollection                  |
| Chat messages            | `event_chat_messages` table            | `events/{eventId}/messages/{messageId}` subcollection         |
| Chat reports             | `event_chat_message_reports` table     | `events/{eventId}/messageReports/{reportId}` subcollection    |
| Group drive participants | `event_group_drive_participants` table | `events/{eventId}/groupDriveParticipants/{uid}` subcollection |

**Index for events list:** Composite index: `status ASC, startsAt ASC`.

**Transaction requirements:** RSVP update is idempotent (set document by UID); no transaction needed. Group drive join uses a transaction to update participant status and prevent race conditions.

**Security-rule requirements:**

- Events: any authenticated user can read published events; admin-only write.
- RSVPs: active members create/read their own RSVP; admin reads all.
- Chat messages: active members read published messages (blocking filter applied client-side or via callable); active members write own messages; admin removes messages.
- Group drive participants: active session required to join; owner can update own status.

**Cost consideration:** Chat messages can grow large. Apply `limit(50)` for initial load; use `startAfter` for pagination. Archive old event chat to Cloud Storage if volumes grow.

---

## Chat → Firestore with bounded queries

**Path:** `events/{eventId}/messages/{messageId}`

**Read pattern:** Load last 50 messages on open; `startAfter` cursor for older messages; Firestore `onSnapshot` listener for new messages.

**Write pattern:** Each message is a single Firestore write. Callable function validates membership, checks block list, sanitizes text, and writes.

**Moderation:** Soft-delete: `removedAt` field set; original text preserved for admin audit; clients receive placeholder text for removed messages.

**Blocking:** Callable function filters messages from blocked users before returning, or client applies a local block filter from a cached block list.

**Cost consideration:** Each Firestore `onSnapshot` listener counts as 1 read per returned document per connection. Bound the listener to the last 50 messages. Disconnect listener on screen exit.

---

## Points ledger → Firestore transactions

**Path:** `pointsLedger/{uid}/entries/{entryId}` (subcollection for ledger entries)  
**Balance:** `pointsLedger/{uid}.balance` (denormalized total, updated atomically)

**Transaction pattern:**

```
runTransaction:
  1. Read pointsLedger/{uid}.balance (or initialize to 0)
  2. Write new entry document to pointsLedger/{uid}/entries/
  3. Update pointsLedger/{uid}.balance += delta
```

**Transaction requirements:** All balance updates (award, spend) must use Firestore transactions to prevent concurrent write races.

**Security-rule requirements:** `pointsLedger/{uid}.balance` and `pointsLedger/{uid}/entries/` — owner read; no client write. Backend (Admin SDK) writes only.

**Cost consideration:** Each point award = 1 transaction (2–3 reads + 2 writes). Low volume at MVP scale.

---

## Kronjakt claims → callable functions + Firestore transactions

**Path:** `crownHuntClaims/{claimId}` + `crownHuntPoints/{pointId}`

**Callable function (`submitCrownHuntClaim`) logic:**

1. Verify Firebase ID token and `activeMember` claim.
2. Look up `crownHuntPoints/{pointId}` to get geofence coordinates.
3. Validate client-reported position is within geofence (server-side using `crown-hunt-geo.ts` logic).
4. Validate speed ≤ `KRONJAKT_MIN_SPEED_KMH` (from client-reported speed, with risk scoring).
5. Validate stationary time ≥ `KRONJAKT_MIN_STATIONARY_SECONDS`.
6. Check active live-location session in RTDB (required for claim validity).
7. Check cooldown: no successful claim on this point within cooldown period.
8. Evaluate risk score (impossible jump, mock-location signal, Play Integrity / App Attest).
9. Firestore transaction: write `crownHuntClaims/{claimId}`, award Kronpoäng via `pointsLedger` transaction.

**Transaction requirements:** Claim creation + points award in a single transaction.

**Security-rule requirements:** `crownHuntClaims/` — no client write. Backend (callable function via Admin SDK) writes only.

**Anti-fraud risk:** High. Must preserve all validation logic from `lib/crown-hunt-service.ts`, `lib/crown-hunt-geo.ts`, and `lib/crown-hunt-risk.ts`.

---

## Feature flags → backend-controlled Firestore configuration

**Path:** `config/featureFlags` — ONE flat document with a camelCase boolean field per flag key (a per-key document path like `config/featureFlags/{key}` would be an invalid odd-segment document path; the flat document is also what every flag-gated domain has read since Phase 9h). Key names, defaults, and descriptions live in contracts/features/feature-flags.json — the canonical registry.

**Read pattern:** Direct SDK read (authenticated, rules-gated); client fetches flag state on app launch and on focus return and falls back to the contract defaults when the document or a field is absent. No realtime listener needed for MVP (poll on resume is sufficient).

**Write pattern:** Admin-only callable `admin.setFeatureFlag` (merge-set, closed key namespace — unknown keys rejected); each change commits atomically with an `adminAuditEvents` record.

**Security-rule requirements:** Any authenticated user can read; admin-only write.

**Cost consideration:** Feature flag reads are infrequent (once per session). Cache result in app memory; no listener subscription needed.

---

## Notifications → FCM + durable in-app notification documents

| Aspect               | Legacy                                              | Target                                                                      |
| -------------------- | --------------------------------------------------- | --------------------------------------------------------------------------- |
| Push delivery        | `notification-service.ts` via a push provider       | Firebase Cloud Messaging (FCM) via `firebase-admin` messaging API           |
| Token storage        | `push_device_registrations` table (encrypted token) | `userPrivate/{uid}/pushTokens/{tokenId}` (encrypted or FCM token hash only) |
| In-app notifications | `user_notifications` table                          | `notifications/{uid}/items/{notificationId}`                                |
| Preference storage   | `notification_preferences` table                    | `userPrivate/{uid}.notificationPreferences` map                             |

**FCM send pattern:** Callable function sends FCM via `admin.messaging().send()`; does not store tokens in plaintext. Token registration (Phase 9l) stores the SHA-256 hash only, as the `pushTokens` document ID; `sendPushNotification` delivery ships with the end-of-MVP Firebase console/FCM setup.

**In-app notification path:** `notifications/{uid}/items/{notificationId}` — `category`, `title`, `previewText`, `body?`, `actionType`, `relatedEntityId?`, `batchId?`, `read`, `readAt?`, `createdAt` (the legacy contract shape; contracts/schemas/notifications.schema.json). Owner-only read; backend-only write — delivery via the `writeInAppNotification` writer (deleted users receive nothing, suspended users only the essential account notices, per-category opt-outs honored except essential categories), read-state via `notifications.markRead` / `markAllRead`.

**Security-rule requirements:** `notifications/{uid}/items/` — owner read; no client write.

**Cost consideration:** FCM is free. In-app notification documents: paginate with `limit(20)`. Delete old read notifications via scheduled cleanup function (retain unread for 30 days, read for 7 days).

---

## Files → Cloud Storage

| Content                          | Storage path                                |
| -------------------------------- | ------------------------------------------- |
| Profile images                   | `profileImages/{uid}/{imageId}`             |
| Vehicle images                   | `vehicleImages/{uid}/{vehicleId}/{imageId}` |
| Ride route data (compressed GPS) | `rideRoutes/{uid}/{rideId}/route.bin`       |
| Ride map preview images          | `rideRoutes/{uid}/{rideId}/preview.png`     |
| Partner logos                    | `partnerLogos/{companyId}/{imageId}`        |
| Billboard images                 | `billboardImages/{billboardId}/{imageId}`   |

**Security-rule requirements:**

- Profile/vehicle images: owner read/write; any authenticated user reads profile images.
- Ride routes: owner read/write only.
- Partner/billboard images: any authenticated user read; admin write only.

**Cost consideration:** Storage costs are per GB stored and per GB downloaded. Use Cloud Storage CDN caching for partner logos and billboard images. Compress route data before upload.

---

## Admin authorization → Firebase Authentication and custom claims

| Aspect              | Legacy                                      | Target                                                          |
| ------------------- | ------------------------------------------- | --------------------------------------------------------------- |
| Admin session       | Custom `Session` with `user.role = 'admin'` | Google Sign-In → Firebase ID token + `admin: true` custom claim |
| Authorization check | `requireAdminHook` reads DB role            | Callable function middleware reads `decodedToken.admin`         |
| Claim management    | N/A                                         | `setAdminRole` callable function (admin-only)                   |

**Risk:** Admin claims must be set server-side only. The Firebase console must be restricted; no direct claim manipulation allowed outside the callable function path.

---

## External integrations and webhooks → HTTP Cloud Functions

| Integration                             | Legacy                                  | Target                                                                  |
| --------------------------------------- | --------------------------------------- | ----------------------------------------------------------------------- |
| Apple subscription receipt verification | `subscription-service.ts` via HTTP call | HTTP Cloud Function (or callable function wrapping HTTP call to Apple)  |
| Google Play receipt verification        | `subscription-service.ts` via HTTP call | HTTP Cloud Function (or callable function wrapping HTTP call to Google) |
| GitHub Issues creation (diagnostics)    | `diagnostics-service.ts`                | HTTP Cloud Function triggered by callable function threshold check      |

**Pattern:** HTTP Cloud Functions are used only for external webhooks or integrations requiring HTTP. Internal trusted operations use callable functions.

**Security:** HTTP function endpoints validate signatures or tokens from the external provider. Never expose raw Firestore or RTDB data to external parties.

---

## Scheduled cleanup → scheduled Cloud Functions

| Job                           | Trigger         | Logic                                                                                            |
| ----------------------------- | --------------- | ------------------------------------------------------------------------------------------------ |
| Expire live location sessions | Every 5 minutes | Delete `liveLocation/{uid}/session` and `liveLocation/{uid}/latest` where `expiresAt` has passed |
| Clean partner insights events | Daily           | Delete `partnerInsightsEvents/` documents older than 7 days                                      |
| Clean read notifications      | Weekly          | Delete `notifications/{uid}/items/` documents where `read: true` and older than 7 days           |
| Clean old diagnostics         | Monthly         | Archive or delete `diagnosticsReports/` older than 90 days                                       |

**Cost consideration:** Scheduled functions have no minimum instances cost when idle. At MVP scale (20–30 users), scheduled function invocations are infrequent and inexpensive.

---

## Migration risk summary

| Domain                     | Risk level | Notes                                                                             |
| -------------------------- | ---------- | --------------------------------------------------------------------------------- |
| Authentication / sessions  | Low        | Firebase token verification already exists in legacy API                          |
| User roles / custom claims | Medium     | Requires mapping existing admin UIDs to Firebase UIDs                             |
| Live location              | Low        | Ephemeral; no data migration required                                             |
| Events and RSVP            | Low        | Small dataset; JSON export + Firestore import if needed                           |
| Chat messages              | Medium     | May have a history worth preserving; consider archiving                           |
| Points ledger              | High       | Financial-adjacent; requires atomic transaction design; test thoroughly           |
| Kronjakt claims            | High       | Anti-fraud logic complexity; must be ported exactly; risk of claim manipulation   |
| Subscriptions              | High       | Revenue-critical; Apple/Google verification must be correct; test on real devices |
| Partner insights           | Medium     | Privacy-critical; aggregation threshold enforcement must be correct               |
| Saved drives               | Low        | Small dataset; no external dependency                                             |
| Files / Cloud Storage      | Low        | Move files via gsutil or Storage API                                              |
| Admin authorization        | Medium     | Admin UIDs must be mapped and claims set before admin web cutover                 |
