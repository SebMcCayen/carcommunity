# Shared clubs: combined security rules

This sidecar extends CarCommunity's **combined ruleset**, never Driver's emulator-only subset. No deployment, activation, production writes or iOS source changes are included. Draft review must cover old-client compatibility and ordered backend/index/rules rollout.

## Authority and privacy

Shared access requires an authenticated token with neither suspended nor banned claims and an existing canonical `users/{uid}` with native Timestamp `onboardingCompletedAt`. Stored suspended/deleted/banned flags and status `deleted`, `suspended`, `deletionPending` or `banned` deny club authority. These checks are scoped to the new club grants; existing non-club grants are unchanged. No `driverUsers` profile or subscription is required. Global/Driver admin claims grant no club rights.

Active canonical users may GET `config/sharedClubs`, including absence, but cannot list or write it. Store only safe `enabled`, `publicAttendanceReady` and activation/stats metadata there. Directory, roster, invitations, chat, calendars and attendance require `enabled == true` and native Timestamp `activatedAt`; missing or malformed activation fails closed even when enabled/readiness are true. Config and owner account GETs remain available while disabled. Clients hide the feature and clear/dispose sensitive state on false/absence/denial. Backend callables independently enforce activation; legacy mutations must delegate/fail closed, never fall back to old multiple-membership behavior.

New club grants require active `schemaVersion: 2` documents. Unmigrated legacy documents remain unreadable through these grants. Preflight conflicts before migration; preserve IDs and never silently resolve multiple memberships. **Firestore exposes entire documents:** public club roots must contain only the contract's directory data, aggregate stats and public owner UID. Never store private member identities, messages, ride/source records or history there. Backend projection tests must enforce this; rules cannot redact fields.

Effective membership requires an active club, current member document, matching `driverClubAccounts/{uid}.activeClubId`, and no `deletionRevoked` tombstone. Roster/chat/calendar use this authority. Self GET of a **missing** member doc remains allowed under an active club to observe removal; an existing stale membership without a matching lock grants no read. Lock docs allow owner GET only, even when disabled; history and unknown descendants remain denied.

Invitation reads require the recipient (document UID and `recipientUid` agree), or current manager. Owners match both `ownerUid` and member role `owner`; moderators require authoritative role `moderator` and current membership. The cross-club invitation query belongs to the Admin SDK state callable: no recursive client grant is added for every collection named `invitations`.

Club writes, locks, history, chat/moderation, invitations, summaries and badges remain server-only. Existing canonical profile allowlists reject badge assignment, nested edits and deletion. KCC edits profiles directly through Firestore; there is no KCC profile callable. The Driver owner must separately regression-test its existing strict profile callable against `clubBadge` payloads.

Public `events/{eventId}/clubs/{clubId}` is a server-only projection of exactly `{clubId,name,logoUrl}` (no identities). Android also accepts the transitional `clubName` spelling. Both GET and LIST require an active canonical account, config `enabled == true`, native Timestamp `activatedAt`, `publicAttendanceReady == true`, and a canonical event with status `published` and native Timestamp `startsAt > request.time`. Exact start, past start and missing/malformed start deny fresh reads immediately, without waiting for projection cleanup. Direct document-ID-ordered list pages also require limit<=25. This uses only three distinct shared authority documents, independent of the number of returned clubs. Both initial listeners and server-confirmed cursor pagination use that query; document-ID ordering needs no additional composite index. Passage of time alone is not evidence that an existing watch is invalidated; clients must also expire their displayed attendance state.

An unfiltered query cannot prove per-result key/type predicates or read 25 distinct parent clubs within the 10-document rules budget. The list therefore trusts this dedicated server-owned public projection, whose strict three-field shape must be enforced and tested by the backend. Single-document GET retains a field/type allowlist (canonical `name` or transitional `clubName`, exactly three keys) and active-club checks as defense in depth.

Every v2 archive transaction atomically sets `publicAttendanceReady: false`, records the revocation cutoff and creates the deterministic pending projection job. **Both public summary GETs and lists fail closed globally during cleanup**: missing, false or malformed readiness metadata denies reads, even while `enabled` is true. The completion CAS may restore exactly `true` only when no other pending jobs remain and the job version/cursor/config transaction is still current. This shared config lookup preserves the three-document page budget and prevents stale archived summaries from remaining publicly readable during paged withdrawal. Private membership/chat/calendar archival denial remains immediate and independent of this public marker. Rule tests model the gate and listener revocation during the source-cleanup mutation; the backend's transaction/CAS regression tests must separately prove the writer protocol.

The local emulator did not invalidate an existing watch on a dependency-only config write. Regression coverage therefore forces a fresh SDK context to verify immediate denial of a new server request, then deletes the archived source summary while the marker is closed and requires listener permission denial **before any changed page is delivered**. Do not treat a timeout on dependency-only invalidation as proof of a rule failure or assume the test proves automatic listener teardown without a source change. Existing downloaded data still requires client clearing.

Clients must observe readiness changes as well as activation. On false/absence, clear public attendance state and cancel the listener; on true, create a new listener. A permission-denied snapshot listener is terminated and does not revive when the marker reopens. Load-more requests must also honor the marker and use server reads.

Initial activation must leave the marker absent/false until the approved operator completes preflight/projection validation and explicitly seeds `publicAttendanceReady: true`. No such production write is part of this sidecar. Parent review must verify the archive/cleanup/activation protocol before rollout.

Published-event/config/account checks remain immediate on every list request; the readiness marker also gates single-document summary GET. Missing, draft, cancelled or completed events do not grant attendance reads. Private calendar freshness/cancellation is enforced by backend projections/callables in addition to the rules' membership gate. No read grant applies to a root private/draft event.

Statistics are only aggregate totals from explicitly saved canonical drives whose complete start/end interval falls within membership. No individual rides, routes, live location or source contributions are exposed by these rules. The human approval for saved-during-membership statistics does not widen any read grant.

All other sidecar collections remain denied, including operations, cleanup, event jobs/revocations and ride contributions. No existing staff queue policy is broadened for club-message reports.

## Logo objects

`clubLogos/{clubId}/{ownerUid}/{filename}` permits authenticated reads and immutable owner creates: active v2 club, active canonical account, 1..262144 bytes, JPEG (`.jpg`/`.jpeg`) or WebP (`.webp`). Creation checks the same canonical suspended/deleted/banned flags and restricted statuses plus suspended/banned claims, using the already-read user document. Creation explicitly requires `resource == null`: the Storage upload operation can otherwise replace an existing object despite the `allow create` label. No admin override, updates, deletes or prefix enumeration. Replacement uses a new name and a backend-approved path; cleanup is backend-only.

Storage uses two Firestore documents, its cross-service maximum: canonical account and authoritative club. It does not additionally read config/account lock. Disabled activation therefore prevents linking uploads through callables and denies club data, but does not revoke public authenticated logo reads or valid owner creates. Canonical deletion must revoke club ownership/status before identity recreation. MIME/extension rules cannot decode bytes: the backend must validate metadata and generate only approved own-bucket URLs, never accept arbitrary external URLs.

## Bounded queries and indexes

| Query                                                                                      | Index / bound                                                          |
| ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------- |
| Backend directory: status active, memberCount DESC, ID ASC                                 | Composite, max25                                                       |
| Direct directory: same plus schemaVersion==2                                               | Composite, max25                                                       |
| Backend invitation collection group: recipientUid/status pending/expiresAt>now             | Composite recipient/status/expiry, max25                               |
| Backend message export/erasure: kind sharedClub + authorUid                                | Collection-group composite; no cross-app broadening                    |
| Roster ID ASC                                                                              | Automatic, max25                                                       |
| Public attendance document ID ASC, cursor + limit25                                        | Automatic; initial listener and server-only Load more                  |
| Chat createdAt DESC                                                                        | Automatic, max50                                                       |
| Private calendar startMillis>now, startMillis ASC + ID ASC                                 | Automatic, direct max25; Admin SDK callable may fetch26 for pagination |
| Event maintenance collection group: projectionType shared-club-event-v1 + startMillis<=now | Collection-group composite, limit100                                   |
| Event jobs status pending, ID ASC                                                          | Automatic, limit10                                                     |
| Membership history joinedAt<=drive start, joinedAt DESC                                    | Automatic, server-only, limit2                                         |

Coordinate query changes with backend/events owners. Cursor pagination is required; existing indexes/field overrides are preserved. Index existence does not grant client reads.

## Verification and release gate

Reserve the parent's shared Firebase emulator slot **before boot or loading rules**: rules tests and Driver integration cannot overlap. Tests use `demo-test`, dedicated fixture paths, and restore prior config state. No production credentials/connections.

Alternatively, use entirely isolated Firestore/Storage emulator ports and a separate demo project. This test file honors `GCLOUD_PROJECT`, `FIRESTORE_EMULATOR_HOST` and `FIREBASE_STORAGE_EMULATOR_HOST`, rejects non-demo projects and non-loopback endpoints, and retains the existing default ports for the shared-slot command below. Its exact-start regression uses a separate demo namespace and freezes only the production event comparison's clock; real-clock past/future GET and list25 cases still execute the unmodified rules. No Android emulator is needed.

With reserved local Firestore:8080 and Storage:9199:

```sh
NODE_OPTIONS=--max-old-space-size=768 pnpm --dir functions test:emulator --maxWorkers=1 src/__tests__/rules-shared-clubs.emulator.test.ts
NODE_OPTIONS=--max-old-space-size=768 pnpm --dir functions test:emulator --maxWorkers=1 src/__tests__/security-rules.emulator.test.ts src/__tests__/rules-social-graph.emulator.test.ts src/__tests__/rules-free-social.emulator.test.ts
```

Every emulator test is registered in exactly one feature group in `functions/scripts/emulator-test-groups.mjs`; this suite belongs to Security Rules, Admin & Platform Core. The existing full-partition coverage guard remains mandatory and unchanged.

Focused cases cover canonical-only eligibility/restrictions, activation, bounded directory/private queries and 25-item attendance listener/server cursor pagination, readiness false/absent/malformed handling, live listener denial on atomic archive/readiness revocation and safe reopening after cleanup, public/private scope, global-role spoofing, all direct writes, live self-removal observation, former-member chat get/query/send, lock revocation, deletion tombstone, archive revocation, badge forgery, owner-only immutable logos and exact size/type bounds. Existing suites verify established platform policies.

Emulator success does not prove deployed composite index readiness or backend projection correctness. Parent review must verify strict public DTOs, deletion/ownership cleanup, legacy migration, the readiness archive/cleanup CAS protocol, activation metadata and backend schema tests before any separately approved deployment. No merge/deploy is authorized here.

Earlier sidecar validation: 33 shared-club rules tests, 364 existing security/social-graph/free-social rules regressions, and 18 runner/coverage-guard unit tests passed. All 79 emulator test files remain assigned exactly once across the existing four CI groups. The runtime used demo-test with Firestore/RTDB/Storage only, JVM heaps capped at 512 MiB, Node at 768 MiB and one serial test worker; all owned emulator clients/servers were then stopped and their ports released. TypeScript test-project typecheck, focused ESLint, formatting and diff checks passed. Full CI rerun and combined backend/client rollout review remain separate gates; no deployment or production configuration write was performed.

Bounded hardening validation (2026-10-28): against base `fe81f6e`, the new activation/start/account/logo regressions failed in 26 cases because access was incorrectly allowed; the valid server-confirmed list25 control passed. With the patch, all 60 cases in `rules-shared-clubs.emulator.test.ts` passed, including the separate deterministic exact-start namespace, live real-clock GET/list25 controls, private membership and immutable-logo cases. Focused strict TypeScript checking, ESLint, formatting and diff checks passed. Only this rules test file was executed, from a disposable copy of the exact changed rules/test files with the repository's pinned test-tool versions. Firestore used 127.0.0.1:18086, Storage 127.0.0.1:19196, hub/logging/websocket 14406/14506/19156, and project `demo-shared-clubs-rules`; Node heaps were capped at 512 MiB and Java heaps at 384 MiB with two active processors. The runtime had isolated credential configuration and a Node loopback-only connection guard. No Functions, RTDB or Android emulator, production access, deployment, or shared-slot emulator was used. These focused results do not replace parent CI/review or production acceptance.
