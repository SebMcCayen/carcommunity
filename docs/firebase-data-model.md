# Firebase data model

This document defines the initial Firebase data architecture for carcommunity: Cloud Firestore collections, Realtime Database paths, and Cloud Storage paths.

For PostgreSQL data model (current `services/api` implementation) see [data-model.md](data-model.md). This document covers the target Firebase-native backend described in [ADR-001](adr/001-firebase-platform.md).

## Overview

| Concern                                            | Service                    |
| -------------------------------------------------- | -------------------------- |
| Durable user and application data                  | Cloud Firestore            |
| Ephemeral realtime state (live location, presence) | Firebase Realtime Database |
| User-uploaded files and ride routes                | Cloud Storage for Firebase |

## Cloud Firestore

### Conventions

- Document IDs are either Firebase UIDs (for per-user collections) or auto-generated IDs.
- `createdAt` and `updatedAt` always use `FieldValue.serverTimestamp()` — never client-side timestamps.
- Do not store raw GPS coordinate arrays in Firestore. Ride route data lives in Cloud Storage; Firestore stores only the storage path and metadata.
- All list queries must use cursor-based pagination (`startAfter`, `limit`). No unbounded collection reads.
- Protected fields (`role`, `admin`, `activeMember`, `suspended`, `deleted`, `entitlement`) are backend-only; Security Rules block client writes to them.

---

### `users` — public user profile

Document ID: Firebase UID.

| Field                   | Type         | Notes                                                                                |
| ----------------------- | ------------ | ------------------------------------------------------------------------------------ |
| `displayName`           | `string`     | Visible username                                                                     |
| `avatarPath`            | `string?`    | Cloud Storage path, e.g. `profileImages/{uid}/{imageId}`                             |
| `bio`                   | `string?`    | Short profile description                                                            |
| `role`                  | `string`     | `'user'` \| `'admin'` — **backend-managed only**                                     |
| `activeMember`          | `boolean`    | Subscription entitlement — **backend-managed only**                                  |
| `suspended`             | `boolean`    | Moderation state — **backend-managed only**                                          |
| `deleted`               | `boolean`    | Soft-delete flag — **backend-managed only**                                          |
| `onboardingCompletedAt` | `Timestamp?` | Written by `auth.completeOnboarding` — **backend-managed only**; null until complete |
| `createdAt`             | `Timestamp`  | Server timestamp                                                                     |
| `updatedAt`             | `Timestamp`  | Server timestamp                                                                     |

Security: any authenticated user can read; owner can update non-protected fields; backend (Admin SDK) manages protected fields.

---

### `userPrivate` — sensitive user data

Document ID: Firebase UID.

| Field                        | Type         | Notes                                                                  |
| ---------------------------- | ------------ | ---------------------------------------------------------------------- |
| `email`                      | `string?`    | Contact channel, not identity key                                      |
| `phone`                      | `string?`    | Optional                                                               |
| `notificationPreferences`    | `map?`       | Push/email preferences                                                 |
| `ageConfirmedAt`             | `Timestamp?` | Consent audit record — written by `auth.completeOnboarding` only       |
| `termsAcceptedAt`            | `Timestamp?` | Consent audit record — written by `auth.completeOnboarding` only       |
| `privacyPolicyAcceptedAt`    | `Timestamp?` | Consent audit record — written by `auth.completeOnboarding` only       |
| `anonymousPartnerStatsOptIn` | `boolean`    | Privacy setting; defaults to `false` (explicit opt-in); owner-editable |
| `createdAt`                  | `Timestamp`  | Server timestamp                                                       |
| `updatedAt`                  | `Timestamp`  | Server timestamp                                                       |

Security: owner-only read and write. No other users may access this collection. Consent timestamps are backend-written compliance records — clients cannot set, modify, or delete them, and the document itself cannot be deleted by clients (account deletion is a backend workflow).

---

### `users/{uid}/badges/{badgeKey}` — awarded badges (Phase 9f)

Document ID = badge key (contracts/schemas/badges.schema.json), which makes
awards naturally idempotent. The catalog definition (name, description,
iconIdentifier — Swedish, positive, non-competitive wording) is denormalized
onto the document. `{ badgeKey, name, description, iconIdentifier, source:
'automatic' | 'admin_manual', awardedByUserId, awardedAt }`.

Security: owner-only read (the legacy API never exposes other users'
badges); all writes are backend-only — automatic evaluators
(garage.addVehicle → `garage_created`; events.complete → `first_event` /
`five_events` via the backend-only `badgeProgress/{uid}` attendance
counters) and the admin-only `badges.awardHelpfulMember` callable. Badges
are never revoked.

---

### `vehicles` — user's garage

Document ID: auto-generated.

| Field       | Type        | Notes                                                    |
| ----------- | ----------- | -------------------------------------------------------- |
| `userId`    | `string`    | Owner Firebase UID                                       |
| `make`      | `string`    | Vehicle make, e.g. `'Volvo'`                             |
| `model`     | `string`    | Vehicle model                                            |
| `year`      | `number`    | Model year                                               |
| `color`     | `string?`   | Optional color description                               |
| `imagePath` | `string?`   | Cloud Storage path, e.g. `vehicleImages/{uid}/{imageId}` |
| `createdAt` | `Timestamp` | Server timestamp                                         |
| `updatedAt` | `Timestamp` | Server timestamp                                         |

Security (Phase 9e): any authenticated user can read; all writes go through the member-only `garage.addVehicle` / `garage.updateVehicle` / `garage.deleteVehicle` callables (per-user cap of 5, strict schemas, storage cleanup on delete). `powertrain`, `engineDescription`, and `description` complete the field list above; `imagePath` follows `vehicleImages/{uid}/{vehicleId}/{imageId}`.

> **Note:** Registration plate numbers must **not** be stored on the shared `vehicles` document, as it is readable by any authenticated user. If the feature requires storing a plate, it must be written to the owner's `userPrivate` document (owner-only access) and never exposed on the publicly readable `vehicles` record.

Composite index: `userId ASC, createdAt DESC` (user's garage list, paginated).

---

### `rides` — saved drive history

Document ID: auto-generated.

| Field              | Type        | Notes                                                                              |
| ------------------ | ----------- | ---------------------------------------------------------------------------------- |
| `userId`           | `string`    | Owner Firebase UID                                                                 |
| `title`            | `string?`   | Optional user-given title                                                          |
| `distanceMeters`   | `number`    | Total distance                                                                     |
| `durationSeconds`  | `number`    | Total duration                                                                     |
| `startedAt`        | `Timestamp` | Ride start time                                                                    |
| `endedAt`          | `Timestamp` | Ride end time                                                                      |
| `routePath`        | `string`    | Cloud Storage path to compressed route, e.g. `rideRoutes/{uid}/{rideId}/route.bin` |
| `previewImagePath` | `string?`   | Cloud Storage path to static map preview                                           |
| `createdAt`        | `Timestamp` | Server timestamp                                                                   |

**Route GPS points are never stored in Firestore.** They are encoded, compressed, and stored as a single file in Cloud Storage under `rideRoutes/{uid}/{rideId}/` (route.bin + preview.png — both member-gated; route visuals are withheld from non-members, matching the legacy member-only routeOverview).

Security (Phase 9d): owner-only read — membership NOT required, so drives saved during a previous membership stay listable. All writes go through callables: `drives.save` computes the stats server-side (client writes could forge distance/duration) and `drives.delete` removes the Cloud Storage files together with the document. `averageSpeedMetersPerSecond` and a nullable `sourceSessionId` (idempotent save retries) complete the field list above. No top-speed field is ever stored.

Composite index: `userId ASC, createdAt DESC` (user's ride history, paginated).

---

### `friends` — accepted friendships

Document ID: auto-generated.

| Field       | Type        | Notes                                    |
| ----------- | ----------- | ---------------------------------------- |
| `userId`    | `string`    | The user who owns this friendship record |
| `friendId`  | `string`    | The friend's Firebase UID                |
| `createdAt` | `Timestamp` | Server timestamp                         |

Security: either party (`userId` or `friendId`) can read; `userId` owner can delete.

---

### `friendRequests` — pending friend requests

Document ID: auto-generated.

| Field        | Type        | Notes                                       |
| ------------ | ----------- | ------------------------------------------- |
| `senderId`   | `string`    | Sender Firebase UID                         |
| `receiverId` | `string`    | Receiver Firebase UID                       |
| `status`     | `string`    | `'pending'` \| `'accepted'` \| `'declined'` |
| `createdAt`  | `Timestamp` | Server timestamp                            |
| `updatedAt`  | `Timestamp` | Server timestamp                            |

Security: sender or receiver can read; sender can create/delete; receiver can update (accept/decline).

Composite indexes: `receiverId ASC, status ASC, createdAt DESC` and `senderId ASC, status ASC, createdAt DESC`.

---

### `events` — community calendar

Document ID: auto-generated. Split-document model (Phase 9b): the exact
location and long description are member-only in the legacy API, so they live
in a separate member-gated document under `details/private` while
`events/{eventId}` carries only teaser-safe and operational fields
(contracts/schemas/events.schema.json `eventTeaser` vs `eventDetail`).

| Field             | Type         | Notes                                                              |
| ----------------- | ------------ | ------------------------------------------------------------------ |
| `title`           | `string`     | ≤200 chars                                                         |
| `summary`         | `string?`    | ≤2000 chars, teaser-safe                                           |
| `startsAt`        | `Timestamp`  |                                                                    |
| `endsAt`          | `Timestamp?` |                                                                    |
| `approximateArea` | `string`     | Coarse area shown to non-members, ≤200 chars                       |
| `isOfficial`      | `boolean`    |                                                                    |
| `status`          | `string`     | `'draft'` \| `'published'` \| `'cancelled'` \| `'completed'`       |
| `cancelledAt`     | `Timestamp?` | Set by `events.cancel`                                             |
| `rsvpCounts`      | `map`        | `{ going, maybe, not_going }` — maintained by `events-onRsvpWrite` |
| `createdByUserId` | `string`     | Admin UID                                                          |
| `createdAt`       | `Timestamp`  | Server timestamp                                                   |
| `updatedAt`       | `Timestamp`  | Server timestamp                                                   |

#### `events/{eventId}/details/private` — member-gated detail

| Field          | Type      | Notes           |
| -------------- | --------- | --------------- |
| `description`  | `string?` | ≤10000 chars    |
| `locationName` | `string?` | ≤200 chars      |
| `address`      | `string?` | ≤400 chars      |
| `latitude`     | `number?` | Paired with lng |
| `longitude`    | `number?` | Paired with lat |

#### `events/{eventId}/rsvps/{uid}` — member RSVPs

Document ID = responding user's UID. `{ status: 'going' | 'maybe' |
'not_going', updatedAt }` — direct Security-Rules-gated member writes;
`events-onRsvpWrite` maintains the parent `rsvpCounts`.

Security: authenticated users read published `events/{eventId}` (teaser);
active members additionally read `details/private` of published events and
write their own RSVP; admins read everything. All mutations go through the
audited `events.*` admin callables — no client writes, including admins.

Composite index: `status ASC, startsAt ASC` (upcoming events list, paginated).

---

### `companies` — partner companies (Phase 9i)

Document ID: auto-generated. `{ name (≤150), category, description (≤1000),
website, phone, address, latitude?, longitude? (map markers), logoPath
(companyImages/{companyId}/…), status: draft|active|paused|ended,
sourceApplicationId?, createdByUserId, createdAt, updatedAt }`
(contracts/schemas/partners.schema.json).

Security: authenticated read while ACTIVE; all writes via the audited
admin `partners.*` callables (lifecycle draft → active ⇄ paused → ended;
ended is terminal; only draft/paused editable).

---

### `offers` — partner offers, three-tier privacy (Phase 9i)

Document ID: auto-generated. The legacy API exposes offers in three tiers,
and Firestore rules cannot redact fields per-read, so each tier is its own
document (contracts/schemas/partners.schema.json):

- `offers/{offerId}` — TEASER: `{ companyId, partnerCompanyName
(denormalized), title (≤150), teaserText (≤250), offerType, status:
draft|active|paused|ended|expired, availableFrom?, availableUntil?,
createdAt, updatedAt }`. Authenticated read while active. Never contains
  the description, terms, discount metadata, or code.
- `offers/{offerId}/details/member` — member-gated while the offer is
  active: description (≤2000), redemptionInstructions (≤1000), terms
  (≤2000), percentageDiscount, fixedDiscountMinorUnits, currencyCode.
  STILL no code.
- `offers/{offerId}/secret/code` — fully backend-only: the discount code
  (≤100) is served exclusively by `partners.showOfferCode` (member, active
  offers only) and is never logged.

All writes via the audited admin `partners.*` callables. Composite index:
`companyId ASC, status ASC, createdAt DESC`.

#### `users/{uid}/savedOffers/{offerId}` — saved offers

Pure member bookmark, document ID == offerId, `{ offerId, savedAt }` —
direct rules-gated writes (owner + active member).

#### `partnerApplications/{applicationId}` — partner applications

Contact data — never client-readable. `{ companyName, organizationNumber?,
category, contactName, contactEmail, contactPhone?, websiteUrl?,
proposedDescription?, proposedAddress?, message?, status:
submitted|under_review|approved|rejected|withdrawn, submittedByUserId,
reviewedByUserId?, reviewNote?, partnerCompanyId?, submittedAt, decidedAt?,
updatedAt }`. Submitted via `partners.submitApplication` (duplicate-spam
guard: one active application per user or contact email); reviewed via
`partners.reviewApplication` — approval creates the draft company in the
same transaction. Composite indexes: `(status, submittedAt)`,
`(submittedByUserId, status)`, `(contactEmail, status)`.

Composite index: `companyId ASC, active ASC, expiresAt ASC`.

---

### `announcements` — community announcements

Document ID: auto-generated.

| Field       | Type        | Notes            |
| ----------- | ----------- | ---------------- |
| `title`     | `string`    |                  |
| `body`      | `string`    |                  |
| `active`    | `boolean`   |                  |
| `createdAt` | `Timestamp` | Server timestamp |
| `updatedAt` | `Timestamp` | Server timestamp |

Security: any authenticated user can read; admin-only write.

Composite index: `active ASC, createdAt DESC`.

---

### `hazards` — road hazard reports

Document ID: auto-generated.

| Field        | Type        | Notes                                                  |
| ------------ | ----------- | ------------------------------------------------------ |
| `type`       | `string`    | e.g. `'police'`, `'pothole'`, `'obstacle'`, `'camera'` |
| `lat`        | `number`    | Approximate location — rounded, not exact GPS          |
| `lng`        | `number`    | Approximate location — rounded, not exact GPS          |
| `reportedBy` | `string`    | Reporter Firebase UID                                  |
| `createdAt`  | `Timestamp` | Server timestamp                                       |

Security: any authenticated user can read or create (reporter's UID must match); admin-only update and delete.

Composite index: `type ASC, createdAt DESC`.

---

### `crownHuntPoints/{pointId}` — Kronjakt reward points (Phase 9h)

`{ title (≤100), description (≤500), latitude, longitude,
geofenceRadiusMeters (20–150), rewardPoints (1–1000 KP), repeatRule:
once|daily|weekly, status: draft|active|paused|ended, availableFrom?,
availableUntil?, approvedAt?, approvedByUserId?, createdByUserId, createdAt,
updatedAt }` (contracts/schemas/crown-hunt.schema.json).

Security: active members read `status == 'active'` points (map display);
all writes go through the admin `crownHunt.*` callables — activation is a
safety gate requiring an explicit safe-location confirmation and an audited
approval note. Composite index: `status ASC, createdAt DESC`.

#### `crownHuntClaims/{claimId}` — every claim attempt

Document ID = SHA-256(userId ':' idempotencyKey), making duplicate
submissions replays. `{ userId, pointId, result (see contract enum),
claimedAt, distanceMeters?, positionRecordedAt?,
reportedSpeedMetersPerSecond?, pointsAwarded?, balanceAfter?,
pointsLedgerEntryId?, createdAt }`. Owner-only member read (claim history);
writes only via `crownHunt.submitClaim`. Awards commit atomically with the
Kronpoäng ledger entry. Composite indexes: `(userId, createdAt)`,
`(userId, result, claimedAt)`, `(userId, pointId, result, claimedAt)`.

#### `crownHuntClaimRisk/{claimId}` — anti-fraud data (backend-only)

`{ userId, pointId, riskScore (0–100), riskReasons[], createdAt }` — fully
backend-only: risk thresholds and reasons must never reach mobile clients,
so they live apart from the owner-readable claim records.

---

### `pointsLedger/{uid}` — Kronpoäng wallet (Phase 9g)

`{ balance, updatedAt }` — denormalized total, updated atomically with every
entry in one Firestore transaction (the mapping's read-balance → append-entry
→ update-balance pattern; the Firestore equivalent of the legacy PostgreSQL
advisory lock).

#### `pointsLedger/{uid}/entries/{entryId}` — append-only ledger

`{ transactionType: earn|spend|adjustment_credit|adjustment_debit|reversal,
source, amount (signed), balanceAfter, description, idempotencyKey,
relatedEntityType, relatedEntityId, createdByUserId, createdAt }`
(contracts/schemas/points.schema.json). Entries are never updated or
deleted; corrections are compensating entries. Idempotent automated awards
use the idempotencyKey as the document ID. A balance can never go negative;
suspended/deleted users earn and spend nothing.

Security: owner-only read (wallet + history); all writes are backend
transactions — the internal creditPoints/debitPoints primitives and the
admin-only `points.adminAdjust` / `points.adminReverse` callables.

---

### `events/{eventId}/messages/{messageId}` — event chat (Phase 9c)

Document ID: auto-generated. Replaces the retired `communityMessages`
scaffold collection — legacy chat is event-scoped
(docs/migration/backend-domain-mapping.md "Chat → Firestore with bounded
queries"; contracts/schemas/event-chat.schema.json).

| Field               | Type         | Notes                                                        |
| ------------------- | ------------ | ------------------------------------------------------------ |
| `authorUserId`      | `string`     | Sender Firebase UID                                          |
| `authorDisplayName` | `string`     | Denormalized at post time                                    |
| `message`           | `string`     | Plain text ≤1000 chars; empty string once removed            |
| `moderationState`   | `string`     | `'visible'` \| `'removed'` — soft-remove, never hard-deleted |
| `removedAt`         | `Timestamp?` | Set by `events.removeChatMessage`                            |
| `removedByUserId`   | `string?`    | Moderating admin UID                                         |
| `createdAt`         | `Timestamp`  | Server timestamp                                             |

Security: active members with a `going`/`maybe` RSVP read while the event is
published; admins read always. All writes go through callables:
`events.postChatMessage` (member, ~5 msgs/30 s rate limit) and
`events.removeChatMessage` (admin soft-removal; original text preserved in
the adminAuditEvents record).

#### `events/{eventId}/messageReports/{reportId}` — chat moderation queue

Document ID: deterministic `messageId_reporterUid_reason` (repeat reports
upsert silently). `{ messageId, reporterUserId, reason, details, status,
reviewedAt, reviewedByUserId, createdAt }`. Written via
`events.reportChatMessage`; never client-readable — reporter identities stay
private.

Composite index (collection group `messages`): `authorUserId ASC, createdAt
ASC` (rate-limit window query; admin cross-event listing).

---

### `moderationReports` — abuse and content reports

Document ID: auto-generated.

| Field        | Type        | Notes                                        |
| ------------ | ----------- | -------------------------------------------- |
| `reportedBy` | `string`    | Reporter Firebase UID                        |
| `targetType` | `string`    | e.g. `'user'`, `'message'`, `'event'`        |
| `targetId`   | `string`    | ID of the reported entity                    |
| `reason`     | `string`    | Report category                              |
| `details`    | `string?`   | Optional additional context                  |
| `status`     | `string`    | `'pending'` \| `'reviewed'` \| `'dismissed'` |
| `createdAt`  | `Timestamp` | Server timestamp                             |

Security: any authenticated user can create (reporter's UID must match); admin-only read and update.

Composite index: `status ASC, createdAt DESC` (admin review queue).

---

### `errorReports` — client error telemetry

Document ID: auto-generated.

| Field        | Type        | Notes                                                                  |
| ------------ | ----------- | ---------------------------------------------------------------------- |
| `userId`     | `string`    | Reporting user's Firebase UID                                          |
| `platform`   | `string`    | `'ios'` \| `'android'` \| `'admin'`                                    |
| `errorCode`  | `string`    |                                                                        |
| `message`    | `string`    | Sanitized error message — must not contain tokens, credentials, or PII |
| `stackTrace` | `string?`   | Sanitized                                                              |
| `appVersion` | `string?`   |                                                                        |
| `createdAt`  | `Timestamp` | Server timestamp                                                       |

Security: any authenticated user can create; admin-only read.

---

### `adminAuditEvents` — admin action audit log

Document ID: auto-generated. **Written by backend (Admin SDK) only. No client writes.**

| Field        | Type        | Notes                                                      |
| ------------ | ----------- | ---------------------------------------------------------- |
| `adminId`    | `string`    | Admin Firebase UID                                         |
| `action`     | `string`    | e.g. `'user.suspend'`, `'offer.create'`, `'hazard.delete'` |
| `targetType` | `string?`   | Entity type affected                                       |
| `targetId`   | `string?`   | Entity ID affected                                         |
| `details`    | `map?`      | Safe summary of the change                                 |
| `createdAt`  | `Timestamp` | Server timestamp                                           |

Security: admin-only read; no client writes.

---

### `accountDeletionRequests` — user-initiated deletion

Document ID: Firebase UID of the requesting user.

| Field       | Type        | Notes                        |
| ----------- | ----------- | ---------------------------- |
| `userId`    | `string`    | Firebase UID                 |
| `reason`    | `string?`   | Optional stated reason       |
| `status`    | `string`    | `'pending'` \| `'processed'` |
| `createdAt` | `Timestamp` | Server timestamp             |

Security: owner can create and read their own request; admin-only write and full read.

---

### `subscriptions` — subscription entitlement records

Document ID: Firebase UID. **Written by Cloud Functions only after receipt verification. No client writes.**

| Field         | Type         | Notes                                      |
| ------------- | ------------ | ------------------------------------------ |
| `userId`      | `string`     | Firebase UID                               |
| `entitlement` | `string`     | Internal name, e.g. `'member_monthly'`     |
| `status`      | `string`     | `'active'` \| `'expired'` \| `'cancelled'` |
| `platform`    | `string`     | `'ios'` \| `'android'`                     |
| `expiresAt`   | `Timestamp?` |                                            |
| `updatedAt`   | `Timestamp`  | Server timestamp                           |

Security: owner can read their own subscription; no client writes; admin-only full access.

---

## Firebase Realtime Database

Use Realtime Database **only** for frequently changing ephemeral state. Do not store durable data or historical records here.

### `/liveLocations/{uid}`

Latest live location for an active location-sharing session.

```json
{
  "lat": 57.5,
  "lng": 12.0,
  "timestamp": 1700000000000
}
```

| Field       | Type     | Notes                                                                              |
| ----------- | -------- | ---------------------------------------------------------------------------------- |
| `lat`       | `number` | Current latitude                                                                   |
| `lng`       | `number` | Current longitude                                                                  |
| `timestamp` | `number` | Unix milliseconds — clients must ignore records older than a short TTL (e.g. 60 s) |

Rules:

- Write: authenticated owner (`auth.uid == $uid`) only.
- Read: authenticated active members (`auth.token.activeMember == true`) only.
- Record is removed immediately when the sharing session ends ("Hide me now").
- Do not store multiple records per user or build a location history here.

### `/presence/{uid}`

Online/offline presence for UI indicators.

```json
{
  "online": true,
  "lastSeen": 1700000000000
}
```

| Field      | Type      | Notes                                   |
| ---------- | --------- | --------------------------------------- |
| `online`   | `boolean` | Whether the user is currently connected |
| `lastSeen` | `number`  | Unix milliseconds                       |

Rules:

- Write: authenticated owner only.
- Read: any authenticated user.

### Reserved paths (future)

| Path                                     | Purpose                              |
| ---------------------------------------- | ------------------------------------ |
| `/convoyPresence/{convoyId}/{uid}`       | Active convoy participant state      |
| `/convoyMessages/{convoyId}/{messageId}` | Temporary in-convoy chat (short TTL) |

---

## Cloud Storage

### Path conventions

- Use generated file identifiers (UUIDs or random IDs). Do not trust original filenames.
- Validate content type and file size in Security Rules.
- Ownership is enforced by path segment matching the user's Firebase UID.

### Paths

| Path                                             | Max size | Content type                                                 | Reader              | Writer     |
| ------------------------------------------------ | -------- | ------------------------------------------------------------ | ------------------- | ---------- |
| `profileImages/{userId}/{imageId}`               | 5 MB     | `image/jpeg`, `image/png`, `image/webp`, `image/gif`         | Authenticated users | Owner      |
| `vehicleImages/{userId}/{imageId}`               | 10 MB    | `image/jpeg`, `image/png`, `image/webp`, `image/gif`         | Authenticated users | Owner      |
| `rideRoutes/{userId}/{rideId}/{filename}`        | 50 MB    | Binary (e.g. `application/octet-stream`, `application/gzip`) | Owner only          | Owner      |
| `ridePreviewImages/{userId}/{rideId}/{filename}` | 5 MB     | `image/jpeg`, `image/png`, `image/webp`, `image/gif`         | Authenticated users | Owner      |
| `companyImages/{companyId}/{imageId}`            | —        | Image                                                        | Authenticated users | Admin only |
| `offerImages/{companyId}/{imageId}`              | —        | Image                                                        | Authenticated users | Admin only |

### Ride route storage flow

1. Record GPS points in device memory during an active drive.
2. On save: encode points to compact format (e.g. Google Encoded Polyline), optionally gzip-compress.
3. Upload to `rideRoutes/{userId}/{rideId}/route.bin`.
4. Write ride metadata (distance, duration, storage path) to the Firestore `rides` collection.
5. On discard: do not upload. Discard the in-memory data entirely.

**One Firestore document per GPS point is never acceptable.** Route data is always a single binary file in Cloud Storage.

---

## Security rules summary

All services are deny-by-default. Rules grant only what is explicitly required.

| Rule                                             | Enforcement                                                     |
| ------------------------------------------------ | --------------------------------------------------------------- |
| Unauthenticated access denied                    | Catch-all `allow ... if false` in all three services            |
| Owner reads/updates own profile                  | `users/{userId}`: `isOwner(userId)`                             |
| Users cannot change role or entitlement          | Firestore `noProtectedUserFieldsChanged()` helper               |
| Private data owner-only                          | `userPrivate/{userId}`: `isOwner(userId)` only                  |
| Vehicle/ride ownership                           | Document `userId` field checked against `request.auth.uid`      |
| Live location reads restricted to active members | RTDB: `auth.token.activeMember == true`                         |
| Subscription records are backend-only writes     | `subscriptions/{userId}`: read-only for owner, no client writes |
| Admin audit log is backend-only writes           | `adminAuditEvents/{eventId}`: no client writes                  |
| Admin access to protected collections            | `isAdmin()` checks `request.auth.token.admin == true`           |
| Storage path ownership                           | Path UID segment matched against `request.auth.uid`             |

Security Rules source files:

- Firestore: `firebase/firestore.rules`
- Realtime Database: `firebase/database.rules.json`
- Cloud Storage: `firebase/storage.rules`

Emulator rule tests: `functions/src/__tests__/security-rules.emulator.test.ts`

Run with:

```sh
cd functions
pnpm emulators:test
```
