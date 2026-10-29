# Personal club calendar subscriptions

Backend-only contract for the accompanying Android controls. No native iOS code,
OAuth, provider API, scheduler, new package, rule grant, deployment, or production
configuration is included. Any calendar application supporting a private HTTPS
iCalendar subscription can consume the same URL; importing a downloaded file is
a static copy and does not subscribe. Provider refresh schedules are outside our
control. The feed suggests one hour; changes are not instant push updates.

## Callable wire contract

Region `europe-west1`, default CarCommunity Functions codebase, Firebase callable
name **`clubCalendar-manage`** (registry `clubCalendar.manage`). Firebase Auth and
production App Check are required. Payloads reject additional fields:

| Request                                              | Success response                                            |
| ---------------------------------------------------- | ----------------------------------------------------------- |
| `{action:"status", clubId}`                          | `{active:boolean, version:number, limits}`                  |
| `{action:"issue", clubId, expectedVersion:number}`   | `{active:true, url, webcalUrl, version, createdAt, limits}` |
| `{action:"revoke", clubId, expectedVersion?:number}` | `{active:false, version:number}`                            |

`limits` is `{maxCurrentEvents:128, maxRememberedEvents:256,
pollIntervalSeconds:3600, retentionDays:400}`. `createdAt` is ISO8601 UTC. All
roles, including an ordinary club member, may subscribe for themselves. The
feature is independent of a paid subscription. Public club badges grant nothing.

Read status before issuing; a never-issued subscription has `version:0`. Every
successful **issue replaces the caller's previous link**, preserving that same
membership's calendar journal and UIDs. Present a rotation warning when status is
active. Require `expectedVersion` from status, so concurrent issue requests yield
one successful link and one `aborted`, rather than two apparently successful
links with an already invalid first result. Other members are unaffected. After
`aborted`, refresh status and require a fresh user action. Revoke optionally
accepts the version to prevent a stale dialog revoking a newer link.

The URL is returned once. Status never returns a token, hash, or recoverable URL.
The Android adapter must keep it in memory, mark clipboard content sensitive,
avoid saveable state/logging/analytics, and explain that sharing the URL shares
access. Reopen a dialog through status; a new link requires explicit issue.

Callable errors contain fixed messages, never SDK errors or document contents:

| Code                  | Meaning                                                         |
| --------------------- | --------------------------------------------------------------- |
| `unauthenticated`     | Sign in first.                                                  |
| `invalid-argument`    | Invalid/extra fields, IDs or missing issue version.             |
| `permission-denied`   | Account/session/access/membership cannot be verified.           |
| `failed-precondition` | Shared clubs or calendar-subscription readiness is unavailable. |
| `aborted`             | Supplied version is stale, or transaction retries exhausted.    |
| `resource-exhausted`  | Revision capacity exceeded.                                     |
| `unavailable`         | Temporary backend failure or corrupt subscription state.        |

Revoke is an authenticated owner cleanup operation and deliberately does not
require still being a club member or the shared feature remaining enabled. It
returns no calendar data. Disabled/deleted Auth accounts and revoked sign-in
sessions still fail. Issuing and status require current canonical membership and
`config/sharedClubs.calendarSubscriptionsReady === true`. Until operator readiness
is verified, the UI should show feature unavailable on `failed-precondition` and
allow retry. No additional client flag, new request field, or response shape is
needed; the callable remains authoritative.

## Subscription HTTP endpoint

Export **`clubCalendar-feed`**, GET/HEAD only:

```text
https://europe-west1-{GCLOUD_PROJECT}.cloudfunctions.net/clubCalendar-feed/calendar.ics?token={43-character-base64url-secret}
```

Origin comes from trusted runtime project configuration, never the inbound Host
header or client input. `webcalUrl` is a convenience scheme variant; HTTPS is the
canonical transport. There is no HTML page, redirect, embedded source/token URL,
analytics, organizer identity, attendee list, coordinates, address or description.
Plaintext application requests are rejected. The strict `request.protocol`
check is retained; there is no manual `X-Forwarded-Proto` fallback.

Proxy/path source verification: the official
[Functions Framework server](https://github.com/GoogleCloudPlatform/functions-framework-nodejs/blob/main/src/server.ts)
explicitly enables Express `trust proxy`. Google's
[Functions header contract](https://cloud.google.com/functions/docs/reference/headers)
states that its load balancer terminates TLS and adds `X-Forwarded-Proto` based on
the client's protocol. Firebase's
[HTTP routing contract](https://firebase.google.com/docs/functions/http-events#invoke_an_http_function)
adds the function name as the external prefix to app routes. For this managed
alias, `/clubCalendar-feed/calendar.ics` therefore routes to the app's
`/calendar.ics`, with `request.protocol === 'https'`. The locally installed
`firebase-functions` 7.4.0 `onRequest` wrapper passes the request through without
rewriting this path. The local Firebase emulator runtime also enables `trust
proxy`, but emulator code is not evidence of deployed edge behavior.

The cloud-injected Framework package is not installed locally, and the deployed
runtime version/edge has not been inspected or called here. These are source and
official-platform contract checks, not deployed end-to-end acceptance. An
unknown-token 404 cannot distinguish an early HTTPS/path rejection from reaching
the service. A future authorized valid-token 200/304/revocation check is still
required after logging readiness. Do not reuse this proxy assumption on a
self-hosted/directly exposed container or a different reverse proxy; its trusted
ingress configuration would need separate verification.

The provider cannot supply Firebase Auth/App Check: its 256-bit random URL bearer
is intentionally the authentication mechanism for this endpoint. Do not weaken
App Check on the callable. Possession delegates this one member's current club
calendar until rotation/revocation/account or membership loss.

Every successful GET/HEAD, **including 304**, does all of the following:

1. Validate canonical token encoding, SHA-256 lookup and matching current owner
   subscription inside a Firestore transaction.
2. Read current Firebase Admin Auth identity, require enabled/existing account,
   and compare `tokensValidAfterTime` against the stored issuance epoch. A second
   Auth read after building closes an Auth revocation during the fetch.
3. In the same consistent Firestore snapshot, verify canonical `users/{uid}`
   onboarding Timestamp, restriction flags/status; sharedClubs enabled and native
   activation Timestamp and `calendarSubscriptionsReady === true`; active v2 club;
   current lock/member/role/ownership;
   exact native joinedAt seconds/nanoseconds and current open membership history
   interval. Rejoining the same club creates a different epoch and cannot revive
   an old URL. Claims cannot override these checks. No Driver-only account mirror
   or paid subscription is required.
4. Verify current CarCommunity product/identity blocks with the existing pure
   access helpers, unioning trusted Auth provider keys and stored identity keys.
   Respect the existing server access policy. Do not initialize access or write
   RTDB mirrors from a provider GET.
5. Read a bounded private intent page and freshly read each relevant canonical
   event, Driver club/event revocation marker and durable pair-withdrawal marker. Never trust cached projection
   titles/dates or an old ETag. Apply Driver's effective-intent cutoff semantics.

Auth-time precision is the Firebase Admin SDK contract, not local fetch time.
The locally inspected Admin SDK 13.10.0 floors refresh-token revocation
`validSince` to UTC seconds (`auth-api-request.js`), constructs
`tokensValidAfterTime` from `parseInt(validSince, 10) * 1000`
(`user-record.js`), and rejects an ID token only when
`auth_time * 1000 < Date.parse(tokensValidAfterTime)` (`base-auth.js`). This
service uses the same strict comparison: equal-second newly authenticated tokens
remain valid even when issue happens at, for example, `second + 999 ms`. It does
not compare the JWT's whole-second `auth_time` against a millisecond local clock.
The focused regression uses the real SDK UserRecord wire constructor. As with
Admin SDK revocation itself, authentication before/after revocation inside the
same second cannot be distinguished at finer precision; do not promise that.

`publicAttendanceReady` gates public attendance summaries; it is not an extra
grant to private calendar data. This endpoint reads private member intents,
keeps the `sharedClubs` activation/active membership gates, and does not read or
broaden the public attendance rules. All checks are snapshot decisions; no HTTP
implementation can erase bytes a provider already fetched or recall a response
after the authorization snapshot has completed.

| HTTP status | Meaning                                                                                               |
| ----------- | ----------------------------------------------------------------------------------------------------- |
| `200`       | Complete `text/calendar; charset=utf-8` representation.                                               |
| `304`       | Current complete representation has matching `If-None-Match`, after reauthorization and source reads. |
| `404`       | Same fixed body for malformed/unknown/rotated/revoked/restricted link or closed feature.              |
| `405`       | Unsupported method; `Allow: GET, HEAD`.                                                               |
| `429`       | Best-effort per-instance admission cap; retry after 60 seconds.                                       |
| `503`       | Source/Auth/backend failure, corruption, or capacity exceeded; retry after 3600 seconds.              |

There is no successful empty substitute on any error, no partial feed and no
stale 304. Errors use `Cache-Control:no-store`. Successful representations use
`private, no-cache, max-age=0, must-revalidate`; intermediaries must not serve
cached private data without invoking the endpoint. No content or authorization
cache bypasses fresh reads. No-change polls do **zero writes**. A changed journal
is one transaction write. Security headers include `Referrer-Policy:no-referrer`,
`nosniff`, no content sources/frames and HSTS. Never follow a redirect with a token.

## Change, deletion and retention semantics

`clubCalendarSubscriptions/{sha256(uid)}` is a server-only personal subscription
record. It holds only a token hash, membership/Auth epoch, generation, times and
a bounded minimal event journal. `clubCalendarTokens/{sha256(token)}` is a
server-only locator. The existing deny-all catch-all denies all client reads,
lists and writes to both new root collections. No broad Firestore rules change
is required. Token rotation/revoke and journal updates serialize on the owner
record. Do not use raw bearer tokens as document IDs or persist a URL.

The feed uses the private `driverClubs/{clubId}/events` intent plus fresh
`events/{eventId}` data and Driver revocation markers. New entries require a
current effective intent and upcoming published canonical event. Only ID/title,
UTC start/end and optional place name enter the event journal. Stable opaque UIDs
include the membership epoch and event ID, and survive token rotation. Material
changes increase SEQUENCE and update persisted DTSTAMP/LAST-MODIFIED. A retained
per-membership sequence origin supplies a monotonic clock floor, so an ID that
returns after journal retirement cannot reset its revision to zero. Neither
current fetch time nor token URL is inserted in an unchanged ICS body.

Withdrawal, missing/deleted event, cancellation, draft transition, or invalidated
intent produces a **STATUS:CANCELLED tombstone with the original UID and higher
SEQUENCE**. The old safe title/time is retained; new private/draft data is never
copied into a tombstone, and location is removed. Do not assume that omission
universally removes a provider's event. Mixed confirmed/cancelled feeds have no
calendar-wide METHOD:CANCEL. Re-registration restores the same UID with a later
sequence. Previously delivered past appointments remain history after ordinary
projection expiry or canonical completion; deletion/cancellation still cancels
them. Future invalid/malformed source fields fail the fetch rather than erase a
known appointment.

An explicit withdrawal is distinguished from normal projection expiry through
the [shared pair-marker contract](../../../docs/contracts/club-calendar-withdrawals-v1.md).
The consumer stores native `createdAt` seconds/nanoseconds as `consentGeneration`
and reads `driverClubEventRevocations/pair_<hash>` in the same bounded `getAll`.
A matching v1 marker at or after that generation always cancels the row, even
if the next provider poll is after event start/completion. A strictly later
registration survives an older marker. No audit log or new composite index is
used. Malformed markers or failed reads return 503 without overwriting state.
Legacy missing-intent rows without a stored native generation become
conservative cancellation tombstones rather than falsely confirmed history.
Do not enable readiness until the separate Driver marker writer is deployed
and verified; the old writer cannot supply this proof.

Canonical account erasure queries both new personal collections by their stored
owner `uid`, not their hash document ID. This removes journals and all current/
orphan token locators through the existing bounded, retryable purge plan, after
the canonical profile is removed and before Auth deletion. Canonical-profile
checks and transaction retries block issue from recreating rows during purge,
including a later Auth deletion failure. Other members' records and shared
Driver pair markers are preserved. No Driver-only account-deletion hook is
introduced; provider-side copies still cannot be recalled by erasing a token.

Confirmed past history retires after end/start + **400 days**. Tombstones retire
only after **both** their cancellation time and event end/start are older than
400 days. Rotation does not shorten retention. Retirement happens during a poll;
there is no polling job. A client offline for more than this documented window
may miss a cancellation and need to remove/re-add the subscription. Bounded
retention cannot guarantee remote deletion in every provider indefinitely.

The raw intent query reads at most 129 documents to detect the 128 limit. The
retained journal/source union has at most 256 IDs. Neither bound is silently
truncated, cursor-paged into an incomplete ICS, or made to fit by evicting live
tombstones. Above a bound return 503 and preserve the prior journal; ordinary
retirement subsequently releases capacity. A club exceeding the bound _within_
the retention window needs an explicit future capacity/product decision.

On revoked membership/token the endpoint returns generic 404 and reveals no
further event data, even tombstones. It intentionally does not continue granting
old calendar access to try to clean up a provider. Existing downloaded items may
remain until that provider removes them or the user deletes the subscription;
the revoke/leave UI must explain this. A URL bearer cannot remotely unread data.

## Logging prerequisite — not applied by this patch

**Application code does not log the token, request URL or error object. This is
not a claim that a URL bearer can never appear in infrastructure logs.** Cloud
Run automatically emits request logs whose `httpRequest.requestUrl` may contain
the path/query, and an upstream proxy/load balancer can log it too. A query
parameter does not prevent that exposure. `no-referrer`, no HTML/analytics and a
fixed HTTPS origin reduce propagation; they do not redact managed request logs.

Before activation, execute the reviewed, narrowly scoped log exclusion/redaction
plan covering this feed route/service and every
applicable sink/proxy, review log-reader access/retention, then verify with a
disposable token. Exclusions on a sink do not erase historical logs or protect
other sinks. This backend patch performs no config/IAM/Firebase change; the
separately reviewed operational plan governs rollout. Until verified, keep
`calendarSubscriptionsReady` false or absent and treat raw URL exposure to platform
log readers as an activation blocker. Function deployment can proceed with the
gate closed. Never promise zero raw-token logging. Do not include real links in bug
reports, screenshots, terminal commands, telemetry or security scan context.

### Server-side activation gate

Automatic function deployment is fail closed: the new server-only field
`config/sharedClubs.calendarSubscriptionsReady` must be the literal boolean
`true`. Missing, false, null, strings or numbers deny status, issue and every
provider GET/HEAD (including a matching ETag). The field is checked in the same
transactional config read as existing activation, adding no document read. An
old valid bearer does not bypass a closed gate. Revoke remains an authenticated,
idempotent cleanup action while the gate is off. No Firestore grant or Driver
flag is widened or repurposed.

After review/merge and verification of the scoped request-log mitigation, the
operator may separately activate this gate with a transactional compare-and-swap
update to the existing config document, preserving its other fields/history and
rechecking the approved preconditions. Do not replace or seed the whole config
document. This patch performs no configuration write or activation; the gate
stays default closed until that explicitly approved operation succeeds.

## Cost and validation

Both functions use `minInstances:0`, maximum two instances, 256 MiB, 0.5 CPU with
concurrency one and a 30-second timeout. There is no schedule/provider polling,
new paid service or dependency. HTTP admission is a fixed 120 requests/minute per
warm instance, with no token/IP retention; it reduces database amplification but
is not a distributed abuse or billing guarantee and resets on cold start.

For N current intents, U retained union IDs and K identity keys (K <= 4), a
successful poll costs approximately **12 + K + max(1,N) + 3U Firestore reads**, two
Admin Auth lookups and zero/one journal write. Transaction retries are capped at
three and can multiply reads. Field masks select only needed event fields. The
hourly suggestion is advisory; providers choose their poll cadence. Do not claim
the SEK 500 budget is guaranteed by source caps. Monitor actual usage in the
separately authorized rollout; use the documented bounded failure path if the
retention/capacity tradeoff no longer fits actual usage.

Focused no-cloud command (one worker, bounded Node heap):

```sh
cd functions
NODE_OPTIONS=--max-old-space-size=384 node ../scripts/run-no-cloud.mjs -- node node_modules/vitest/vitest.mjs run src/clubCalendar/service.test.ts src/clubCalendar/ics.test.ts src/clubCalendar/http.test.ts src/__tests__/appcheck-guard.test.ts --maxWorkers=1 --no-file-parallelism
```

Tests use a serial in-memory transaction adapter and pure HTTP/ICS seams. They
exercise concurrent issue/CAS, independent members, rotations/revokes, account/
Auth/access/membership/epoch failures, canonical changes and stale intent,
withdrawal/deletion/cancellation, retention/capacity, zero-write unchanged polls,
UTF-8 octet folding/escaping/CRLF, no private field/secret leakage and
authorization-before-304. This does not replace the parent's heavy emulator
slot, actual Firestore transaction/rules verification, deployment metadata,
managed request-log verification or real provider synchronization acceptance.

Local evidence before the proxy/Auth-precision follow-up: 80 focused calendar/App Check tests and nine
structural deployed-instance metadata tests (89 total) passed through `run-no-cloud.mjs`
with one worker and a 384 MiB Node heap. Production TypeScript build, strict
calendar-only source/test typecheck, scoped ESLint, formatting and diff checks
passed. The full repository test-project typecheck reached its deliberately
capped 512 MiB heap; it was not retried with a larger heap on the shared host.
The parent's full CI/security review and heavy emulator/Android/provider QA remain
separate gates. No deployment, live Firebase call, log-sink configuration or
Gradle invocation was performed.

The source/docs-only proxy/Auth-precision follow-up retained production code
unchanged and passed all 65 service tests, including a real-Admin-SDK same-second
authentication boundary regression, through the same no-cloud runner. It did not
call a deployed feed, install a Framework dependency, or repeat the full
test-project typecheck that previously exhausted its heap.

Final pair-consumer/lifecycle follow-up: 106 focused calendar, deletion-plan,
App Check and instance-metadata tests passed without cloud access. The full
backend source/test-project typecheck then passed at a 1536 MiB heap cap in
32.39 seconds (1,025,852 KiB peak RSS). Scoped ESLint and formatting/diff checks
passed. The real canonical account-purge regression is prepared in the existing
account emulator suite but has not run here: it needs Auth, RTDB, Storage and
Functions in addition to Firestore, so the granted Firestore-only slot cannot
establish full-purge evidence. No additional emulator service or Gradle was
started. Parent owns formal review, full CI and deployed producer/consumer QA.

References: [shared club contract](../../../docs/contracts/shared-clubs-v1.md),
[RFC 5545](https://www.rfc-editor.org/rfc/rfc5545),
[RFC 7986](https://www.rfc-editor.org/rfc/rfc7986),
[Cloud Run logging](https://cloud.google.com/run/docs/logging),
[HTTP request log fields](https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#HttpRequest),
[Functions proxy headers](https://cloud.google.com/functions/docs/reference/headers).
