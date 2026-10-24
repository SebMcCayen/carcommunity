# Native Driver read API

Implemented in CarCommunity's default Functions codebase under
`functions/src/driverFeed`, region `europe-west1`. These are new grouped
callables `driverFeed-list` and `driverFeed-detail` (registry names
`driverFeed.list` / `driverFeed.detail`). No deployment is performed by this change.
Driver's repository, production rules and existing `driver-*` callables are not
modified. This document complements the native integration README in this folder.

## Wire contract

`driverFeed-list` takes `{limit?: integer, cursor?: Cursor}` and returns
`{posts: Post[], nextCursor: Cursor | null}`.

`driverFeed-detail` takes `{threadId: string, limit?: integer, cursor?: Cursor}`
and returns `{post: Post, comments: Comment[], nextCursor: Cursor | null}`.
Omit cursor for the first page; explicit `null` and unknown request fields are
invalid. `limit` defaults to 20 and must be an integer between 1 and 20.

All Post fields are required:

```json
{
  "id": "thread_0001",
  "authorUid": "author_0001",
  "authorName": "Canonical display name",
  "authorHandle": "driver_handle",
  "text": "Thread content",
  "createdAtMillis": 1700000000000,
  "mediaUrls": [],
  "likeCount": 0,
  "replyCount": 0
}
```

Comment has exactly `id`, `authorUid`, `authorName`, `authorHandle`, `text`,
and `createdAtMillis`, all required with the same types. `mediaUrls` contains
at most four stored Firebase Storage URLs; the bridge neither signs URLs nor
downloads media. Native clients must use authenticated storage access where
required; returning a URL does not confer Storage permission. Counts are
nonnegative safe integers. Timestamp milliseconds are integers, never null;
malformed content is omitted. No role, private preferences, block lists,
onboarding consent, liked/bookmarked/followed state or fabricated model names
are returned.

`Cursor = {seconds: integer, nanoseconds: integer, id: string}`. Seconds use
Firestore's range [-62135596800, 253402300799]; nanoseconds [0, 999999999].
Document IDs use Driver's existing `^[A-Za-z0-9_-]{8,128}$` restriction.
Feed order is `createdAt DESC, documentId DESC`. Comments are
`createdAt ASC, documentId ASC`. Keep the complete timestamp tuple, even though
display timestamps have only millisecond resolution. Cursors do not load or
require the continued existence of the referenced document and confer no access.

Each call scans at most `limit` raw documents, without a status filter, refill
loop, count query, or per-post callable. Status filtering happens in the server.
`nextCursor` points to the last **scanned** document when the raw page is full.
Short/empty visible pages may therefore have a next cursor; an exactly full
terminal raw page requires a final empty call to discover exhaustion. Clients
must preserve this cursor, expose user-controlled pagination, and avoid an
automatic unbounded refill loop. Query ordering uses the built-in createdAt
index and its same-direction document ID tie; no new composite index is needed.

## Policy and sources

Source baseline: Driver commit `d97583e7cf69f7582688d3466987aafcd98d7217`:
`functions/src/index.ts` (`activeUidOf`, `driverProfileIsActive`, `createThread`,
`createReply`, `toggleUserBlock`, `getNotificationThread`) and
`functions/src/contracts.ts` (`documentId`). KCC ADR 003 and the combined
`firebase/firestore.rules` own shared identity and project policy. This bridge
uses the stricter active-only native read surface, without staff/author exceptions.

- Caller must be authenticated, with no `suspended` auth claim.
- Canonical `users/{uid}` must exist, have a real `onboardingCompletedAt`
  timestamp, and have neither `suspended: true` nor `deleted: true`.
- `driverUsers/{uid}` must exist and have `onboardingComplete: true`. Its
  `status` must be `active` or absent (Driver completeProfile omits status).
  Unknown, suspended, deleted and deletionPending statuses fail closed.
- The same canonical/Driver profile requirements apply to each returned author.
  Canonical `users.displayName` and Driver `driverUsers.handle` supply identity;
  stale `authorName`/`authorHandle` copies on content are not authoritative.
- Both directions of `driverUsers/{uid}/blockedUsers/{otherUid}` and
  `userBlocks/{uid}/blocked/{otherUid}` are checked for every distinct author.
  `blockVisibility/{uid}.hiddenUids` is also honored. Checking actual KCC edges
  closes the mirror's cap/propagation gap. Failed policy reads fail the request.
- A thread/reply must have `status: active`. Hidden, removed, deleted, locked,
  missing-status and malformed documents are not returned. Detail rereads and
  authorizes the parent before querying replies on **every** page, in the same
  Firestore read-only transaction snapshot as replies, profiles and blocks.
- Neither KCC `admin` nor `driverRole` grants a bypass. Driver roles remain
  distinct; this API offers no moderation surface and no paid-membership gate.

No onboarding, terms acceptance, profile creation, migration or content writes
occur. Native request cancellation and clearing cached content on account change
remain native responsibilities. A response is authorized at its transaction's
snapshot time; clients refresh on return and clear unavailable detail content.

## Errors

| Firebase code | `details.reason` | Meaning |
| --- | --- | --- |
| failed-precondition | canonical-onboarding-required | Canonical profile/onboarding missing |
| failed-precondition | driver-onboarding-required | Driver profile/onboarding missing |
| permission-denied | account-restricted | Caller claim, canonical or Driver account restricted |
| not-found | thread-unavailable | Parent absent, inactive, malformed, blocked or author unavailable |

`unauthenticated`, `invalid-argument`, and `resource-exhausted` use standard
Firebase codes without a reason. Backend read failures propagate as callable
errors; clients must never fall back to direct content reads. An invalid stored
last sort key fails with `failed-precondition` rather than producing a cursor
that repeats or skips a page. Messages are technical; native localization uses
codes/reasons, not message text.

## Bounds and cost

List/detail share 120 pages per UID per fixed UTC hour. Atomic rate accounting
uses Driver's existing backend-only `driverRateLimits` collection, action
`nativeFeedRead`, stable document ID `{uid}_nativeFeedRead`, bucket/count and
`expiresAt`. At most one record per viewer is retained without requiring a new
TTL policy. A request validates before any reads; profile authorization precedes
quota mutation. Authorized requests consume a page even if filtered empty or
their parent is unavailable. Corrupt current-bucket counters fail closed.

The charge transaction normally reads three documents (two guards, one rate
record) and writes one, with at most three transaction attempts. The consistent
page snapshot rereads two guards and one visibility document, scans at most 20
content records, then reads at most six documents per distinct author (two
profiles and four block edges), deduplicated within that request. List is at
most 146 document reads including a single successful charge; detail at most
153 (20 comments plus one parent and up to 21 authors). Charge retries add at
most six reads and two writes; there is no page-scan retry/refill loop. Repeated
authors and hidden/inactive content cost less. This trades bounded server joins
for complete two-way block enforcement; it does not promise one Firestore read
per post. At the extreme 120-page cap, one viewer can cause about 18,360 document
reads/hour; expected 20–30-user usage should be assessed before deployment against
the existing SEK 500/month budget. No additional paid service, worker, endpoint,
scheduled cleanup or persistent content cache is introduced.

## Release boundary

This bridge enforces policy for its responses. Existing direct Driver thread
and reply rules still allow broader reads; closing those rules requires a
separate coordinated migration of existing Driver consumers and is outside this
change's ownership. Thus this is **not** project-wide prevention of raw Firestore
bypass. Native clients must exclusively use these two callables for this surface.
Production rollout, App Check registration and full Functions CI remain release
operations owned by the main orchestrator; no production access is used in tests.
