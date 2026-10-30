# Canonical shared-club invitation notifications

Backend-only implementation. Driver remains the sole writer of invitations and
membership. No deployment, configuration change, migration, or cloud backfill is
part of this change. Android integration is owned separately; no iOS changes.

## Client and preference contract

- Category: `club_invite`, optional/social, producer-only. Admin broadcasts cannot
  send it. Missing preferences default to enabled, as for other social categories.
- Preferences: `userPrivate/{uid}.notificationPreferences.club_invite` accepts
  `{inApp?: boolean, push?: boolean}`. `inApp: false` suppresses both surfaces;
  `push: false` preserves the inbox. Global push and `pushPreviews` still apply.
- Inbox: `actionType: open_notifications`, `relatedEntityId: clubId`. No new action
  enum. Existing clients retain the established inbox fallback.
- Push: `target: clubs`, `entityId: clubId`, with existing `category`,
  `notificationId`, and `recipientUid` fields. Club ID selects a destination;
  it never grants membership. The client reloads current canonical invitations
  before presenting accept/decline. A historical inbox item may outlive an invite.
- Generic Swedish notification copy contains no club name, inviter name, or other
  protected content. Existing backend notification copy conventions apply.
- Category lists in the Functions core, TypeScript shared package, and
  language-neutral notification schema include the new value. Social and push
  category coverage includes it. Admin-sendable/essential/action enums do not change.
- Existing Firestore rules permit an owner-writable preferences map, without a
  category-key allowlist. No preference rules change is required. The existing
  owner-only `driverClubAccounts/{uid}` get covers Driver's additive
  `invitationsRevision`; the backend producer does not write that field.

## Producer and consistency

`onClubInvitationWritten` is an `onDocumentWritten` trigger on
`driverClubs/{clubId}/invitations/{recipientUid}`. Only a genuine new pending
creation/generation qualifies. A well-shaped but superseded candidate costs
only four document reads (receipt plus config/invitation/club) and no writes. Status-only edits, deleting a document, the same
createdAt, backwards timestamps, and replacing an unexpired pending generation
with a later createdAt do not qualify. A declined/cancelled/accepted generation
can be followed by a genuinely later invitation; an expired pending generation
can likewise be replaced.

The event is only a candidate. A transaction rereads the current invitation and
checks exact native Timestamp generation/expiry and inviter. It requires:

- `config/sharedClubs.enabled === true` and native, nonfuture `activatedAt`;
- current schema-v2, active, closed club;
- pending invitation with matching path club/recipient, a different valid inviter,
  native nonfuture createdAt, and unexpired expiresAt after createdAt, at most 7 days;
- canonical, onboarded, nonrestricted recipient and inviter, nonrevoked accounts;
- inviter's current matching account/membership generation and owner/moderator role;
- recipient not already a member of this club (another club is allowed);
- no club ban for the recipient;
- neither direction of canonical `userBlocks` or legacy
  `driverUsers/{uid}/blockedUsers` for inviter/recipient, owner/recipient, or
  inviter/owner (deduplicated when the inviter is the owner), matching Driver;
- recipient Firebase Auth record exists and is enabled;
- recipient canonical CarCommunity access/identity blocks, with the existing
  `carCommunityPolicy/access.enforceInitialized` override semantics.

It calls the existing `writeInAppNotification` with the same transaction. The
writer reads user state, private preferences, and the deterministic inbox ID in
that transaction. Existing callers retain their original three-argument behavior.
No producer calls FCM. The existing inbox-created push trigger additionally
rechecks this invitation's generation/current eligibility immediately before its
normal FCM path, preventing queued pushes for expired/declined/superseded/blocked
invitations. As with any external send, changes after the final read and FCM
acceptance cannot recall an already-sent push. The existing push transport retains
its delivery/retry behavior; this change guarantees producer idempotency, not
exactly-once device delivery.

The notification ID is SHA-256 over club, recipient, inviter, and createdAt
seconds/nanoseconds, prefixed `club_invite_`. One server-only high-water receipt at
`clubInvitationDeliveries/{sha256([clubId,recipientUid])}` records the latest
processed createdAt, notificationId, outcome, and processedAt. It shares the inbox
commit. It survives inbox read-state changes, owner deletion, and retention so a
replayed event/backfill cannot recreate the same or an older generation. In-app
opt-outs also receive a receipt so backfills cannot later revive them. It contains
no raw identities. Retain the receipt independently of inbox cleanup; deleting it
would weaken replay protection. The existing default-deny rules cover this new
collection (no client grants).

Transient Firestore/Auth failures propagate and the producer has `retry: true`.
Transactions retry changes to the invitation, club, membership, access, blocks,
preferences, or receipt; no partial inbox/receipt commit is acknowledged. Auth is
read once per eligible invocation and reused on transaction retries. Deleted Auth
users are a terminal suppression; infrastructure errors are retryable.

## Cost bounds

There is no collection scan, collection-group listener, polling schedule, or
broadcast. Invalid transitions cost zero document reads; duplicate generations
cost one receipt read. A successful transaction uses at most 32 fixed document
reads (including up to four identity-block docs and the writer's eligibility/ID
reads), two writes, and one Auth lookup. Transaction contention may repeat those
reads. The delayed-push guard uses at most 29 fixed document reads plus one Auth
lookup, in addition to the existing push flag/token/preferences reads. An owner
invite with no identity keys costs exactly 20 producer reads and two writes; its
push guard costs 17 reads. A moderator with four distinct identity keys is the
32/29-read maximum. Duplicate delivery costs one read and zero writes. One
recipient only. Trigger: minInstances 0, ordinary trigger ceiling 20, CPU 0.5,
concurrency 1, 256 MiB, timeout 60 seconds.

## Existing pending invitations: bounded, idempotent rollout backfill

A trigger deployment does **not** notify invitations that already exist. Keep the
canonical invitations UI/listener working independently, and schedule a separately
reviewed operator backfill if historical notifications are desired. No automatic
startup sweep, rewritten invitation timestamp, or mass touch is permitted.

Use this bounded procedure for a separately approved harness:

1. Prepare a reviewed manifest of at most 25 active closed club IDs per run. Stop
   at that cap; do not scan every collection implicitly. For each club, page its
   direct `invitations` subcollection by document ID using `limit(25)` and
   `startAfter(lastDocument)`, at most four pages (100 rows) per run in total,
   across the entire manifest. Persist a
   per-club cursor and the manifest position; reaching a cap means incomplete,
   never successful completion. No new composite index is needed.
2. Default to dry-run: perform only reads. Parse candidates with the exported
   `invitationGeneration` helper and use `ClubInvitationNotifications.canPush`
   only for already-delivered rows; do not use canPush as a pre-delivery probe
   (it intentionally requires a delivered receipt). Report aggregate counts,
   cursors, and candidate path/createdAt only in the private reviewed manifest,
   never public logs. Dry-run selection is not an authorization certificate.
3. Apply only with separately approved explicit project, manifest and bounded
   page budget. For each candidate call
   `ClubInvitationNotifications.process(clubId, recipientUid, snapshot.data())`
   sequentially through the existing writer. It revalidates all current state,
   preferences and generation in a transaction and creates the same deterministic
   receipt/ID as live delivery. Do not use direct notification writes.
4. Save a cursor only after the page's candidates finish. A crash/restart or live
   trigger overlapping the run is safe: receipts prevent duplicates, including
   inbox deletions. Stop on infrastructure failure, retain the current cursor,
   and resume it after recovery. Never force-deliver malformed, cancelled,
   expired, blocked, opted-out, or superseded invitations.
5. Re-running the same manifest/cursors is safe. New invitations inserted behind
   a saved cursor are handled by the live trigger; a second bounded manifest pass
   can check any rollout overlap. Retain receipts; no destructive migration.

A dry run must not call `process`, since it writes. This document defines the
operator procedure; it does not execute or install a cloud backfill harness.

## Deployment selectors (future separately approved operation only)

After the backend and Android changes are integrated and tested, deploy only the
producer and changed push function, with the explicit approved project:

```text
firebase deploy --project <approved-project> --only functions:onClubInvitationWritten,functions:notifications-onNotificationCreated
```

No rules deployment is necessary for this backend change. The new receipt
collection is already denied by default, preferences are already supported, and
Driver owns the account revision and invitation mutation engine. Do not use a
bare `--only functions`, enable the sharedClubs gate, build a release, or run the
backfill as a side effect of deploying these two functions.

## Backend file manifest

Repository-relative paths for the backend commit (Android files are owned separately):

```text
contracts/schemas/notifications.schema.json
docs/contracts/shared-club-invitation-notifications.md
functions/src/__tests__/notifications-core.test.ts
functions/src/clubInvitations/index.test.ts
functions/src/clubInvitations/index.ts
functions/src/clubInvitations/service.test.ts
functions/src/clubInvitations/service.ts
functions/src/index.ts
functions/src/notifications/deliver.ts
functions/src/notifications/notifications-core.ts
functions/src/notifications/push-core.test.ts
functions/src/notifications/sendPush.ts
packages/shared/src/notifications.ts
```
