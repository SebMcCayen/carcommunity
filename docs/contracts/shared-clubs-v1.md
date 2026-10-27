# Shared clubs v1 — Android CarCommunity and Driver

Implementation contract, 2026-10-27. One Firebase Auth UID and the existing `driverClubs` collection are canonical across both apps. User approved ONE club per account and statistics ONLY for drives within membership. No iOS implementation in this change. Production deployment/activation is a separate owner-approved step.

## Backend ownership and API

Driver repository, `functions:driver`, `europe-west1`; preserve legacy endpoints as adapters when shared-clubs activation is enabled so old clients cannot bypass rules. New callables require App Check and active canonical `users/{uid}` with onboardingCompletedAt, reject suspended/deleted canonical identities. CarCommunity-only accounts must work without inventing a Driver profile. Existing Driver-only content/policy gates stay unchanged. Authoritative config `config/sharedClubs` enabled defaults false and requires a native Timestamp `activatedAt`; fail closed if either is absent. Preserve legacy endpoint names/DTOs, but ALL legacy club mutations MUST delegate to the canonical engine, including when disabled (fail closed, never fall back to old multi-club writes). Public event attendance also requires `publicAttendanceReady === true`. Club archival closes this readiness gate atomically until bounded stale-summary cleanup completes; clients dispose/reattach attendance listeners and cancel pagination when it closes. Missing/false readiness fails closed. Config enabled/statsSince/readiness metadata is authenticated read-only so clients can hide the feature before activation. Document non-destructive migration/preflight for existing multiple memberships; never silently pick/delete clubs.

- `driver-clubsList`: `{cursor?: string, limit?: number}` -> `{items: Club[], nextCursor: string|null}` (max25, deterministic memberCount desc/id).
- `driver-clubsState`: `{}` -> `{club: Club|null, membership: Membership|null, joinAllowedAt: ISO|null, invitations: Invitation[], legacyConflict: boolean}`. Invitations bounded25. Legacy conflict must block create/join/accept, not silently remove memberships.
- `driver-clubsGet`: `{clubId}` -> `{club: Club, membership: Membership|null}`.
- `driver-clubsManage`: discriminated action union; every mutation carries `operationId` (16..160 ASCII idempotency key):
  - `create`: name3..60, description10..500, location2..80, visibility `open|closed` -> `{clubId}`.
  - `update`: clubId, name/description/location/visibility/logoPath optional, owner only. Logo path belongs to same club/owner, immutable upload max256KiB JPEG/WebP, validate storage metadata, generate approved own-bucket URL server-side, no arbitrary external URL.
  - `join`: clubId, open clubs only, self only.
  - `leave`: clubId, self; owner with other members transfers ownership first; sole owner may disband.
  - `invite`: clubId,targetUid, owner/moderator and CLOSED only; recipient accepts, never forced membership; invitation expires7d. User picker reuses public canonical accounts, bounded search.
  - `acceptInvitation`: clubId, self; validate current invitation and current CLOSED visibility, recheck cooldown/current membership transactionally.
  - `remove`: clubId,targetUid, owner/moderator in either visibility; cannot remove owner; moderator cannot remove/demote another moderator or owner. Owner may demote moderator first.
  - `moderator`: clubId,targetUid,enabled boolean, owner only; at most THREE moderators, transaction serializes club counter. Owner is not one of three.
  - `transfer`: clubId,targetUid, owner only to existing member; previous owner becomes member; promoted moderator releases a moderator slot.
  - `disband`: clubId,owner only, bounded asynchronous cleanup if needed, no memberships or chat access remain effective once status becomes archived; members receive cooldown.
- `driver-clubsMembers`: `{clubId,cursor?,limit?}` -> `{items: Member[], nextCursor}`; current members only, max25, no public closed roster.
- `driver-clubsSendMessage`: `{clubId,text,operationId}` -> `{messageId}`; plain text/emoji max2000 codepoints, current membership checked in transaction, rate limits, audit/moderation integration, no client writes. Manage also accepts reportMessage(clubId,messageId,reason,comment?) for members into the existing private Driver admin report queue, and removeMessage(clubId,messageId) for owner/moderator to redact text. Shared club UI reuses canonical blocking-block/blocking-unblock with targetUserId and blockVisibility/{uid}.hiddenUids.
- `driver-clubsEventAttendance`: `{clubId,eventId,attending:boolean,operationId}` -> `{attending}`; owner/moderator only, authoritative event validation, upcoming/noncancelled, club is public participant but member identities are not published.
- `driver-clubsEvents`: `{clubId,cursor?,limit?}` -> `{items: ClubEvent[], nextCursor}`; member-only calendar, max25, ordered startMillis, exclude expired/cancelled/unavailable events. This private calendar does NOT publish private events to the public Driver feed/homepage.

## Stable DTOs

All callable dates ISO8601 UTC strings. Firestore timestamps remain native Timestamp.

`Club`: id,name,description,location,visibility,status (`active|archived`),ownerUid,logoUrl(nullable),memberCount,moderatorCount,stats:{driveCount,distanceMeters,durationSeconds},createdAt,updatedAt.
`Membership`: clubId,role (`owner|moderator|member`),joinedAt.
`Invitation`: clubId,clubName,expiresAt.
`Member`: uid,displayName,avatarUrl(nullable),role,joinedAt.
`ClubEvent`: eventId,title,startMillis,endMillis(nullable),locationName(nullable).

## Authoritative documents

- `driverClubs/{clubId}` public authenticated directory/detail, no private member names or messages; stats aggregate only.
- `driverClubs/{clubId}/members/{uid}` current roster (uid,role,joinedAt), no client writes. Current member read; self get remains possible after deletion to observe revocation. All member rights also require parent status active and active canonical user.
- `driverClubAccounts/{uid}` owner-only GET, callable writes: activeClubId nullable, role,joinedAt,joinAllowedAt nullable. No list. One common per-UID lock enforces membership across simultaneous apps/clubs. History subcollection stores authoritative membership intervals (clubId,joinedAt,leftAt) server-only.
- `driverClubs/{clubId}/invitations/{uid}` recipient or club management read, callable writes only, cannot bypass rechecks.
- `driverClubs/{clubId}/messages/{messageId}` current-member reads only, immutable server-authored text/emoji (authorUid,authorName,text,createdAt); no public history. Clients require SERVER-confirmed current membership at entry, observe account/member revocation, clear/dispose local message state on leave/remove/account change/denial, do not re-open from stale offline membership cache. Existing downloaded knowledge cannot be remotely unread, but app access must be revoked.
- `driverClubs/{clubId}/events/{eventId}` private member calendar projection. `events/{eventId}/clubs/{clubId}` authenticated public club attendance summary (clubId/name/logoUrl, no member identities). Authoritative event rendering must ignore cancelled/past events and missing/archived clubs.
- `clubLogos/{clubId}/{ownerUid}/{fileName}` immutable owner-only image creates <=256KiB JPEG/WebP; authenticated public logo reads; no client overwrite. Owner badge is not a role/trust indicator.
- Canonical `users/{uid}.clubBadge` nullable server-owned `{clubId,clubName,logoUrl}` public affiliation, cleared transactionally on leave/remove. Name/logo update projection must be bounded/idempotent and recheck current club before updating users. Neither client may assign a badge by profile-edit payload.

## Business rules and migration

Create/join/accept require no active membership and `now >= joinAllowedAt`; every effective leave/removal sets shared server-time cooldown now+24h. Create is also a join and cannot evade cooldown. Invitations do not bypass cooldown. A blocked person is never silently assigned. Repeated operationIds cannot reapply counters or extend cooldown. Concurrency tests cover joins in different clubs, fourth moderator race and role changes with sends/removals.

Default old club visibility=open; do not destructively migrate multiple memberships. Use bounded dry-run/preflight and explicit rollout gate before activation. Preserve driverClubs IDs and legacy members/counters/mirrors needed by old clients. Driver profile-only deletion must not delete shared v2 clubs/data still owned by the canonical CarCommunity identity; canonical account deletion must revoke membership and badges/chat, handle ownership, and prevent resurrection. Document/test retention and cleanup.

## Driving statistics

Only canonical saved `rides/{rideId}` (CarCommunity) count. No live GPS history or routes are exposed to club members. Attribute accepted distance/duration/count to the authoritative membership interval covering BOTH drive start and end (and valid server save, including existing duration-only session fallback records); exclude drives predating membership. Use server membership history to survive delayed trigger delivery/leave and do not attribute later drives to old clubs. Stored source contributions and transactional deltas make duplicate/out-of-order/update/delete events idempotent. Re-read current ride, never trust stale event payload. No automatic historical backfill; label statistics since feature activation. Club total only, no speed/individual ranking. Aggregate privacy and cost limits documented.

## Clients and rollout

CarCommunity Social adds Klubben: current member detail/presentation/logo/stats/calendar/management, nonmember directory logos/descriptions/create/invitations/cooldown. Chat adds Klubb tab; member-only chat, no history retained in UI after revocation. Driver supports the same management/roles/visibility/invitations/cooldown and current membership backend. Profile logo overlays ordinary avatar in BOTH clients without obscuring Supporter crown (opposite corner), accessible label/fallback.

Swedish UI and English localization for Android; shared canonical locale generator may mechanically sync iOS catalog only (already user-approved), no iOS implementation. All source changes in branches/PRs; no production configuration/deployment writes as part of coding. Firebase shared rules MUST be combined from CarCommunity, never deploy Driver's emulator-only rule subset. Tests: transactions/rules/chat revocation/cross-app DTOs/Android and Flutter UX, bounded queries, pagination and no UI-only authorization.
