# Shared club-calendar withdrawal markers v1

Driver owns the canonical attendance writer. CarCommunity owns the personal ICS
consumer. This additive storage contract changes no callable input/response,
client role decision, public projection, or read rule. Deploy/verify both sides
before enabling `config/sharedClubs.calendarSubscriptionsReady`.

## Durable pair marker

```text
driverClubEventRevocations/pair_<sha256(JSON.stringify([clubId,eventId]))>
```

Hash the UTF-8 compact JSON array, in club/event order, with lowercase hexadecimal
SHA-256. For `clubId="club"`, `eventId="event"`, the document ID is:

```text
pair_4aaad3756733b252aa037399e464bd004a59ff0f0ddd3b7955caa086ea029c1b
```

The document contains exactly the domain fields needed by the consumer:

```text
{schemaVersion:1, clubId:string, eventId:string, invalidatedThrough:Timestamp}
```

No member UID, bearer, audit identity or private event presentation belongs here.
The existing deny-all rules keep the collection server-only. No new index or
client read grant is needed.

## Writer invariants (Driver)

- An explicit withdrawal atomically writes a native server-commit timestamp to
  `invalidatedThrough`, removes private intent/public summary, and writes the
  existing operation receipt/audit in the same authorized transaction. It still
  writes the marker when ordinary expiry already removed the private intent.
- Read the pair marker before transaction writes. Existing authentication,
  membership, operation replay and rate checks stay authoritative.
- A new registration writes native `createdAt` as the consent generation,
  strictly later than the retained cutoff. Do not clear an older marker on
  reconsent, or manufacture a future generation from a client/wall clock.
- Projection reconciliation preserves `createdAt`. Routine expiry does not write
  a withdrawal marker. Idempotent replay must not extend an old withdrawal cutoff
  after a later registration.
- The marker is durable domain state, not an audit query or a TTL receipt. A
  provider may not poll until after event start/completion. Do not delete it on
  expiry or token/member erasure while an old observed consent can still matter.

## Consumer invariants (CarCommunity)

Each materialized journal row stores the observed native `createdAt` as
`consentGeneration:{seconds,nanoseconds}`. Compare native tuples exactly, never
through `toMillis()`; different generations/cutoffs can share a millisecond.

The existing bounded `getAll` reads canonical event, global event cutoff and
pair marker for every retained ID in the same authorization transaction.
Malformed/mismatched markers and failed reads fail the request without changing
the journal. The one extra point read per retained ID replaces the proposed
audit lookup; no scan or composite index is introduced.

If `invalidatedThrough >= consentGeneration`, emit `STATUS:CANCELLED` with the
same UID and increased sequence. This veto applies even to a stale live intent,
past/completed canonical event, or `completedHistory`. A strictly later consent
survives the old marker. Normal expiry can preserve previously delivered history
only with its native generation and no applicable withdrawal. A legacy missing-
intent row lacking that proof becomes a conservative cancellation tombstone;
absence alone must not become confirmed history.

The existing 400-day feed retention still applies. A client offline longer than
that window may need to remove/re-add its subscription. This bounded retention
does not make a revoked URL capable of remotely deleting downloaded bytes.

## Rollout and erasure

Parent coordinates the separate Driver PR based on immutable `01504b5`/PR #53,
the CC consumer, and deployment verification. Deployment must include the latest
approved shared-club backend revision (including PR #53's stale-clock correction)
as well as the new pair writer; the initial stack base is not a release approval.
Keep calendar readiness false or
absent until the new canonical writer is deployed, old writer traffic drained,
the producer/consumer cases pass, and request-log protections are verified.
Then activate only through the reviewed CAS on the existing shared config.
No production change is performed by this source contract.

Canonical account erasure also removes the personal
`clubCalendarSubscriptions` and `clubCalendarTokens` rows through their stored
owner `uid`, including orphan token locators. They join the existing retryable
owned-query purge after canonical-profile removal, before Auth deletion. This
does not erase shared pair markers, other members' subscriptions or Driver-only
account state. The separate Driver-only deletion path gains no CC side effect.
