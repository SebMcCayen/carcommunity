# Shared clubs: combined security rules

This sidecar extends CarCommunity's **combined ruleset**, never Driver's emulator-only subset. No deployment, activation, production writes or iOS source changes are included. Draft review must cover old-client compatibility and ordered backend/index/rules rollout.

## Authority and privacy

Shared access requires an authenticated, non-suspended token and an existing canonical `users/{uid}` with Timestamp `onboardingCompletedAt`, neither suspended nor deleted. No `driverUsers` profile or subscription is required. Global/Driver admin claims grant no club rights.

Active canonical users may GET `config/sharedClubs`, including absence, but cannot list or write it. Store only safe `enabled` and activation/stats metadata there. Missing/disabled config denies directory, roster, invitations, chat, calendars and attendance. Clients hide the feature and clear/dispose sensitive state on false/absence/denial. Backend callables independently enforce activation; legacy mutations must delegate/fail closed, never fall back to old multiple-membership behavior.

New club grants require active `schemaVersion: 2` documents. Unmigrated legacy documents remain unreadable through these grants. Preflight conflicts before migration; preserve IDs and never silently resolve multiple memberships. **Firestore exposes entire documents:** public club roots must contain only the contract's directory data, aggregate stats and public owner UID. Never store private member identities, messages, ride/source records or history there. Backend projection tests must enforce this; rules cannot redact fields.

Effective membership requires an active club, current member document, matching `driverClubAccounts/{uid}.activeClubId`, and no `deletionRevoked` tombstone. Roster/chat/calendar use this authority. Self GET of a **missing** member doc remains allowed under an active club to observe removal; an existing stale membership without a matching lock grants no read. Lock docs allow owner GET only, even when disabled; history and unknown descendants remain denied.

Invitation reads require the recipient (document UID and `recipientUid` agree), or current manager. Owners match both `ownerUid` and member role `owner`; moderators require authoritative role `moderator` and current membership. The cross-club invitation query belongs to the Admin SDK state callable: no recursive client grant is added for every collection named `invitations`.

Club writes, locks, history, chat/moderation, invitations, summaries and badges remain server-only. Existing canonical profile allowlists reject badge assignment, nested edits and deletion. KCC edits profiles directly through Firestore; there is no KCC profile callable. The Driver owner must separately regression-test its existing strict profile callable against `clubBadge` payloads.

Public `events/{eventId}/clubs/{clubId}` has a rule-enforced field/type allowlist and contains exactly `{clubId,clubName,logoUrl}` (no identities). Reads require a published event and active club. Page limit5 bounds per-result club lookups plus user/config/event checks within the rules call budget. Stale archived summaries fail closed until backend cleanup; callers must handle denial. Private calendar freshness/cancellation is enforced by the backend projection/callable; rules still enforce membership. No new read grant applies to a root private/draft event.

All other sidecar collections remain denied, including operations, cleanup, event jobs/revocations and ride contributions. No existing staff queue policy is broadened for club-message reports.

## Logo objects

`clubLogos/{clubId}/{ownerUid}/{filename}` permits authenticated reads and immutable owner creates: active v2 club, active canonical account, 1..262144 bytes, JPEG (`.jpg`/`.jpeg`) or WebP (`.webp`). No admin override, updates, deletes or prefix enumeration. Replacement uses a new name and a backend-approved path; cleanup is backend-only.

Storage uses two Firestore documents, its cross-service maximum: canonical account and authoritative club. It does not additionally read config/account lock. Disabled activation therefore prevents linking uploads through callables and denies club data, but does not revoke public authenticated logo reads or valid owner creates. Canonical deletion must revoke club ownership/status before identity recreation. MIME/extension rules cannot decode bytes: the backend must validate metadata and generate only approved own-bucket URLs, never accept arbitrary external URLs.

## Bounded queries and indexes

| Query                                                                                      | Index / bound                                                          |
| ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------- |
| Backend directory: status active, memberCount DESC, ID ASC                                 | Composite, max25                                                       |
| Direct directory: same plus schemaVersion==2                                               | Composite, max25                                                       |
| Backend invitation collection group: recipientUid/status pending/expiresAt>now             | Composite recipient/status/expiry, max25                               |
| Backend message export/erasure: kind sharedClub + authorUid                                | Collection-group composite; no cross-app broadening                    |
| Roster ID ASC                                                                              | Automatic, max25                                                       |
| Chat createdAt DESC                                                                        | Automatic, max50                                                       |
| Private calendar startMillis>now, startMillis ASC + ID ASC                                 | Automatic, direct max25; Admin SDK callable may fetch26 for pagination |
| Event maintenance collection group: projectionType shared-club-event-v1 + startMillis<=now | Collection-group composite, limit100                                   |
| Event jobs status pending, ID ASC                                                          | Automatic, limit10                                                     |
| Membership history joinedAt<=drive start, joinedAt DESC                                    | Automatic, server-only, limit2                                         |

Coordinate query changes with backend/events owners. Cursor pagination is required; existing indexes/field overrides are preserved. Index existence does not grant client reads.

## Verification and release gate

Reserve the parent's shared Firebase emulator slot **before boot or loading rules**: rules tests and Driver integration cannot overlap. Tests use `demo-test`, dedicated fixture paths, and restore prior config state. No production credentials/connections.

With reserved local Firestore:8080 and Storage:9199:

```sh
pnpm --dir functions test:emulator src/__tests__/rules-shared-clubs.emulator.test.ts
pnpm --dir functions test:emulator src/__tests__/security-rules.emulator.test.ts src/__tests__/rules-social-graph.emulator.test.ts src/__tests__/rules-free-social.emulator.test.ts
```

Focused cases cover canonical-only eligibility/restrictions, activation, bounded directory/private queries, public/private scope, global-role spoofing, all direct writes, live self-removal observation, former-member chat get/query/send, lock revocation, deletion tombstone, archive revocation, badge forgery, owner-only immutable logos and exact size/type bounds. Existing suites verify established platform policies.

Emulator success does not prove deployed composite index readiness or backend projection correctness. Parent review must verify strict public DTOs, deletion/ownership cleanup, legacy migration, activation metadata and backend schema tests before any separately approved deployment. No merge/deploy is authorized here.

Current sidecar validation: TypeScript test-project typecheck, focused ESLint, formatting and diff checks passed. Firebase rule compilation/runtime and existing rule suites are pending the parent-reserved emulator slot; do not treat this draft as rollout-ready.
