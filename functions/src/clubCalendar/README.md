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

| Code                  | Meaning                                                      |
| --------------------- | ------------------------------------------------------------ |
| `unauthenticated`     | Sign in first.                                               |
| `invalid-argument`    | Invalid/extra fields, IDs or missing issue version.          |
| `permission-denied`   | Account/session/access/membership cannot be verified.        |
| `failed-precondition` | Shared clubs are not enabled and activated.                  |
| `aborted`             | Supplied version is stale, or transaction retries exhausted. |
| `resource-exhausted`  | Revision capacity exceeded.                                  |
| `unavailable`         | Temporary backend failure or corrupt subscription state.     |

Revoke is an authenticated owner cleanup operation and deliberately does not
require still being a club member or the shared feature remaining enabled. It
returns no calendar data. Disabled/deleted Auth accounts and revoked sign-in
sessions still fail. Issuing and status require current canonical membership.

## Subscription HTTP endpoint

Export **`clubCalendar-feed`**, GET/HEAD only:

```text
https://europe-west1-{GCLOUD_PROJECT}.cloudfunctions.net/clubCalendar-feed/calendar.ics?token={43-character-base64url-secret}
```

Origin comes from trusted runtime project configuration, never the inbound Host
header or client input. `webcalUrl` is a convenience scheme variant; HTTPS is the
canonical transport. There is no HTML page, redirect, embedded source/token URL,
analytics, organizer identity, attendee list, coordinates, address or description.
Plaintext application requests are rejected. The Google Functions Framework
trusts its platform proxy when deriving `request.protocol`; verify that contract
in separately authorized deployed acceptance.

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
   activation Timestamp; active v2 club; current lock/member/role/ownership;
   exact native joinedAt seconds/nanoseconds and current open membership history
   interval. Rejoining the same club creates a different epoch and cannot revive
   an old URL. Claims cannot override these checks. No Driver-only account mirror
   or paid subscription is required.
4. Verify current CarCommunity product/identity blocks with the existing pure
   access helpers, unioning trusted Auth provider keys and stored identity keys.
   Respect the existing server access policy. Do not initialize access or write
   RTDB mirrors from a provider GET.
5. Read a bounded private intent page and freshly read each relevant canonical
   event and Driver club/event revocation marker. Never trust cached projection
   titles/dates or an old ETag. Apply Driver's effective-intent cutoff semantics.

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

Before activation, the operator must explicitly approve and deploy a narrowly
scoped log exclusion/redaction design covering this feed route/service and every
applicable sink/proxy, review log-reader access/retention, then verify with a
disposable token. Exclusions on a sink do not erase historical logs or protect
other sinks. No such config/IAM/Firebase change is authorized or performed here.
Until verified, treat raw URL exposure to platform log readers as a deployment
blocker, never promise zero raw-token logging. Do not include real links in bug
reports, screenshots, terminal commands, telemetry or security scan context.

## Cost and validation

Both functions use `minInstances:0`, maximum two instances, 256 MiB, 0.5 CPU with
concurrency one and a 30-second timeout. There is no schedule/provider polling,
new paid service or dependency. HTTP admission is a fixed 120 requests/minute per
warm instance, with no token/IP retention; it reduces database amplification but
is not a distributed abuse or billing guarantee and resets on cold start.

For N current intents, U retained union IDs and K identity keys (K <= 4), a
successful poll costs approximately **12 + K + max(1,N) + 2U Firestore reads**, two
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

Local evidence for this patch: 74 focused calendar/App Check tests and nine
structural deployed-instance metadata tests passed through `run-no-cloud.mjs`
with one worker and a 384 MiB Node heap. Production TypeScript build, strict
calendar-only source/test typecheck, scoped ESLint, formatting and diff checks
passed. The full repository test-project typecheck reached its deliberately
capped 512 MiB heap; it was not retried with a larger heap on the shared host.
The parent's full CI/security review and heavy emulator/Android/provider QA remain
separate gates. No deployment, live Firebase call, log-sink configuration or
Gradle invocation was performed.

References: [shared club contract](../../../docs/contracts/shared-clubs-v1.md),
[RFC 5545](https://www.rfc-editor.org/rfc/rfc5545),
[RFC 7986](https://www.rfc-editor.org/rfc/rfc7986),
[Cloud Run logging](https://cloud.google.com/run/docs/logging),
[HTTP request log fields](https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#HttpRequest),
[Functions proxy headers](https://cloud.google.com/functions/docs/reference/headers).
