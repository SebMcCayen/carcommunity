# Admin history: cursor contract and rollout

This increment extends the approved admin renewal after PRs #1209 and #1210.
It adds server-authorized pagination to hand-placed crown points, merged Crown
Hunt attempts and each member's point ledger. Existing app claim/award and wallet
contracts are unchanged. The admin still uses the one shared XP/Kronpoäng balance.

## Read contracts

- `admin-listCrownPoints`: optional status, cursor and pageSize (default 20,
  maximum 100). Returns the existing point summary envelope.
- `admin-listCrownClaims`: source `all`, `hand_placed` or `automatic`, optional
  result, cursor/pageSize. Merges crownHuntClaims and crownSpawnClaims, preserving
  origin and distinct result values. Only IDs, safe point title, result, rarity,
  awarded points and time reach the admin. No coordinates, raw risk signals,
  thresholds or movement history are exposed.
- `admin-listPointsLedger`: targetUid, cursor/pageSize. Returns immutable entries
  plus the current authoritative wallet balance. It never sums a page to derive
  balance. Ledger reads do not grant permission to change an owner's points.

Every page calls requireAdminActor, including a continuation/retry. A revoked,
restricted or missing admin profile cannot reuse an earlier cursor. These reads
are scoped to explicit collections; they cannot select arbitrary data paths.

Cursors are versioned base64url data, not authentication credentials. The query
scope and page size are bound to the cursor, with a fixed creation-time cutoff.
Timestamps keep seconds and nanoseconds; document ID breaks ties. Each claim
source has its own last-emitted frontier, so unconsumed fetched records are not
skipped when merging. Counts are server aggregates of the matching date/filter
range. The cutoff excludes new records dated after the first request; this is
not an export with a historical database read-time snapshot. A refresh is the
way to discover newly committed or changed records.

Queries filter in Firestore before limiting. A request fetches at most pageSize

- 1 records per selected source and returns at most pageSize, with at most that
  many safe point-title lookups. No arbitrary 20/200/1000-record scan is used as a
  complete result. Cursor values do not require the previous document to exist.

The UI appends with stable IDs, blocks duplicate continuation requests, preserves
rows/cursor on a failed continuation, and ignores stale responses after a filter
or member change. Refresh begins a new history. Initial read failure is not an
empty list or zero count. Exact local time and source accompany claim rows.

## Owner protection before exposing entry discovery

The existing points-adminReverse callable lacked the owner-target guard used by
points-adminAdjust. It now reads the target role in its transaction, authorizes
before both mutation and replay, and refuses an absent target. It preserves
atomic compensation/balance/audit writes, deterministic reversal ID, no reversal
of a reversal and no overdraft. Existing administrative compensation for a
suspended/deleted member is preserved; no general earning/spending restriction
was weakened. No existing ledger entries are rewritten.

## Isolation and verification

Development admin emulators require a demo-* project and all three explicit
loopback endpoints: VITE_AUTH_EMULATOR_HOST=127.0.0.1:9099,
VITE_FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 and
VITE_FUNCTIONS_EMULATOR_HOST=127.0.0.1:5001. Cloud identity cannot be mixed into a
local database. Production builds ignore emulator endpoints.

The disposable emulator cases cover 45-entry histories, exact timestamp ties,
both sources with overlapping document IDs, deleted cursor documents, later
insertions, risk matches older than the first page, query-bound cursors, all
permission classes, correct current balance, and owner reversal/replay guards.
Web tests cover transport, failed continuation/retry, double click and stale
filter responses. Both native validation workflows run for this admin change.
Actual Android/iOS app-to-backend preview acceptance remains separate from CI.

## Rollout order

Merge the earlier security/foundation PRs before this dependent PR. Deploy the
additive backend read functions and required result/claimedAt and status/createdAt
indexes first; wait for indexes to be READY and verify the indexed queries in a
preview environment. Then deploy the admin web. Old apps and the old admin remain
compatible. This PR performs no deployment or production data writes.
