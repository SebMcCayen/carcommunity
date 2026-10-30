# Canonical shared-club invitation notifications

Backend-only implementation. Driver remains the sole writer of invitations and
membership. The narrow staged Firebase rollout and bounded backfill are within
the already approved shared-club task, after review and merge. This coding run
does not execute production actions. Android integration is owned separately;
no iOS changes.

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
only four document reads (receipt plus config/invitation/club) and no writes.
Status-only edits, deleting a document, the same
createdAt, backwards timestamps, and replacing an unexpired pending generation
with a later createdAt do not qualify. A declined/cancelled/accepted generation
can be followed by a genuinely later invitation; an expired pending generation
can likewise be replaced.

The event is only a candidate. A transaction rereads the current invitation and
checks exact native Timestamp generation/expiry and inviter. It requires:

- `config/sharedClubs.enabled === true`, native, nonfuture `activatedAt`, and
  additive `invitationNotificationsReady === true` (missing/false fails closed);
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

## Approved staged operator rollout

1. Complete the current backend/Driver/Android reviews, merge, and run the focused
   integration test below. No new permission flow is required for the narrow
   rollout already authorized by the user.
2. Deploy the two exact selectors while `invitationNotificationsReady` is absent
   or false. Both producer and queued-push guard default off without changing
   club access or Android invitations UI/listeners:

   ```text
   firebase deploy --project <project-id> --only functions:onClubInvitationWritten,functions:notifications-onNotificationCreated
   ```

3. Verify the deployed selectors and inactive readiness behavior. The operator
   then updates **only** `config/sharedClubs.invitationNotificationsReady` to true
   using a field update/transaction. Preserve every other field, including the
   existing enabled state and native Timestamp activatedAt; do not replace the
   config document. Setting this same field false pauses producer/push again
   without deleting functions or disabling shared clubs. This adds no producer
   or push-guard reads: current() already reads the config document.
4. Run the bounded existing-pending backfill below. Driver's pending dedupe means
   re-inviting an already-pending recipient does not create a fresh generation,
   so this backfill supplies notifications missing at rollout.

No rules deployment is necessary: preferences, owner-only account revision reads,
and default denial of delivery receipts are already covered. Do not use a bare
`--only functions`, change other feature gates, or build/release a mobile package
as a side effect.

## Existing pending invitations: bounded operator harness

`functions/scripts/backfill-club-invitations.mjs` is a manual CLI only. There is
no startup hook, schedule, invitation rewrite, direct inbox write, or collection
group query. Every apply candidate goes through the exact compiled
`ClubInvitationNotifications.process` with its native Firestore snapshot. Build
Functions first so `lib/` matches the reviewed source.

Run from `functions/`, using an existing private directory **outside the repo**:

```text
node scripts/backfill-club-invitations.mjs --project <project-id> --state /private-operator-directory/invites-dry.json --manifest /private-operator-directory/invites-dry-manifest.jsonl
node scripts/backfill-club-invitations.mjs --project <project-id> --apply --state /private-operator-directory/invites-apply.json --manifest /private-operator-directory/invites-apply-manifest.jsonl
```

- Explicit `--project` and absolute `--state` are mandatory; no environment
  project fallback. Conflicting SDK project configuration fails closed. The
  already approved operator runs dry-run/apply after current reviews/merge.
- No `--apply` means zero Firestore writes and no Auth lookup. Dry-run reports
  current-shaped, unexpired pending candidates, not a promise of eligibility or
  delivery. Private local cursor/optional manifest files are its only writes.
- Hard caps per invocation: 25 fetched club documents and 100 fetched invitation
  documents, including inactive clubs/nonpending invitation rows. Invitations
  page at 25 by document ID; every query has a limit. No extra sentinel document
  is read beyond either cap. An exact full page is conservatively incomplete.
- Club discovery itself is bounded by 25; no unbounded discovery precedes the
  caps. Private cursors retain the last completed club and, when needed, the
  current club/last invitation. Rerun with the same state file to continue.
  State is checkpointed only after a whole invitation page completes. A failure
  replays the partial page safely through generation receipts.
- Dry-run and apply state scopes are distinct and project-bound: passing a
  dry-run state to apply fails instead of skipping candidates. Keep using the
  apply state for subsequent bounded apply passes. After a readiness pause or
  for a fresh verification pass, use a **new** apply state file and walk again;
  receipt dedupe makes this safe even after inbox deletion. Never delete receipts.
- Apply first requires shared-club and notification readiness. It then rechecks
  all eligibility per candidate through the service transaction. If readiness
  changes during a pass, ineligible candidates may be skipped; after resuming,
  start a fresh bounded pass as above. Maximum backend writes: two per processed
  eligible generation, at most 200 per run; dry-run writes none.
- Stdout is one aggregate JSON summary (counts, dryRun, incomplete). Cursor
  values, candidate paths/UIDs, credentials, and raw error details are never
  printed. `--manifest` is optional and exclusively creates a new 0600 file;
  choose a fresh manifest filename for each continuation. State/checkpoints and
  the exclusive state lock are also 0600; shared/symlinked state is refused.
- State/manifest files are private operational data and must stay outside Git.
  No notification content or raw Auth/provider credentials are recorded.
- Demo execution requires explicit `--emulator`, a demo-* project, loopback
  FIRESTORE_EMULATOR_HOST, and the repository no-cloud transport preload. Cloud
  credentials are rejected in that mode, and Auth identity is fake there only.
  Production apply uses the real Auth lookup. Neither production mode nor
  emulator mode is inferred implicitly.

A cursor completed at `incomplete: false` covers that pass's key range. Live
triggers cover newly created generations, including keys inserted behind a saved
cursor. A fresh bounded pass can verify rollout overlap; existing generation
receipts make overlapping live delivery/backfill idempotent.

## Focused verification

The real Firestore test imports the service and actual writer directly; it does
not mock transactions or create Auth users. Only identity lookup is fake. It
seeds unique fixture IDs, restores config/policy, and deletes exact owned paths.
The backfill apply test restricts its club query to its own fixture. No database
flush or automatic emulator startup is performed.

With the parent's Firestore emulator already running, from `functions/`:

```bash
GCLOUD_PROJECT=demo-test FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 \
node ../scripts/run-no-cloud.mjs -- ./node_modules/.bin/vitest run \
  --config vitest.emulator.config.ts \
  src/__tests__/club-invitation-notifications.emulator.test.ts --maxWorkers=1
```

This proves inbox/receipt commit timestamps and rollback on a real transaction,
parallel/replayed generation dedupe after deletion, opt-out receipts, a preference
change before writer eligibility reads, obsolete/declined generations, readiness
pause/resume, and idempotent existing-pending harness apply. It does not test FCM
transport or device notification display.

Focused harness unit tests (no emulator/network), from `functions/`:

```bash
node ../scripts/run-no-cloud.mjs -- ./node_modules/.bin/vitest run scripts/backfill-club-invitations.test.mjs --maxWorkers=1
```

For a guarded demo CLI run, use that same no-cloud runner with
`node scripts/backfill-club-invitations.mjs --project demo-test --emulator`, an
explicit absolute private `--state` path and optional `--apply`. Set the loopback
Firestore host as above. No production action is run by the test commands.

## Backend file manifest

Repository-relative backend files (Android files are owned separately):

```text
contracts/schemas/notifications.schema.json
docs/contracts/shared-club-invitation-notifications.md
functions/src/__tests__/notifications-core.test.ts
functions/src/__tests__/club-invitation-notifications.emulator.test.ts
functions/scripts/backfill-club-invitations.mjs
functions/scripts/backfill-club-invitations.test.mjs
functions/scripts/emulator-test-groups.mjs
functions/vitest.config.ts
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

The ordinary Functions Vitest configuration includes
`scripts/backfill-club-invitations.test.mjs`, so
the bounded harness tests also run in the standard unit-test CI gate.
