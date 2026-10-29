# Shared chat flood contract v1

Owner-approved scope: CarCommunity Community/convoy/DM/event sends and the Driver shared-club send callable used by both Android apps. Both backends use the same canonical Auth UID and Firestore database. No role or subscription exemptions. iOS and Android UI work are outside these commits.

`chatSendGuards/{uid}` is backend-only (unmatched paths are denied by both existing rulesets). Its six numeric fields are `policyVersion: 1`, `creditMs: 0..12000`, `lastRefillMillis`, `violationLevel: 0..4`, `lastViolationMillis`, and `blockedUntilMillis`. Milliseconds are server epoch time. Each accepted send consumes 2000 credit; each elapsed millisecond refills one credit up to 12000. The first violation pauses 5s, then 30s, 2m and at most 10m. Thirty minutes without a violation resets escalation even if normal conversation continues. Active pauses never extend or rewrite state. Malformed, unknown-version or future-clock state is repaired once to a bounded 10m pause. No text, fingerprints, room IDs, UID copy, TTL field or telemetry are stored. A replacement write strips unknown fields; normal state is well below 1 KiB. Logical expiry works without physical deletion or a TTL billing configuration.

New budget errors use Firebase `resource-exhausted` with exactly:

```ts
{ reason: 'chat-rate-limited', retryAfterMs: number, // integer 1..600000
  blockedUntilMillis: number, serverNowMillis: number }
```

The server commits a new penalty before throwing the callable error. Clients use server-relative `retryAfterMs`; repeated calls during a pause keep the same absolute deadline. Older generic resource-exhausted errors remain valid: event's rolling 5/30s and club's existing fixed-window 30/min checks retain their existing messages/codes. These legacy caps are checked before the shared guard: a legacy-cap rejection neither consumes shared tokens nor records or escalates a shared-budget violation. This intentionally preserves the stricter existing controls independently of progressive penalties. All checks precede writes. Auth/account/membership/friendship/block authorization precedes old receipts and budget checks. Already accepted receipts replay during a pause with no charge, notification or unread/counter mutation; revoked callers still fail authorization.

Event adds optional `clientId: [A-Za-z0-9_-]{1,64}` used verbatim as its message ID. Legacy callers still receive generated IDs. As on Community/convoy/DM, same-sender retries are first-write-wins and another sender's key is `already-exists`; retries of moderated messages never restore text. Event's bounded recent-message query runs in the same transaction as the shared UID guard and message write, serializing concurrency across events and other channels. Existing message and operation receipt retention is unchanged.

Both repositories carry byte-identical `chat-abuse-policy.ts`, `chat-send-guard.ts`, this contract and `golden.json`. Golden cases and the pinned pure-policy SHA-256 detect accidental drift. Change policy versions and fixtures together across both repositories; do not deploy an unknown version to only one writer. No content/emoji heuristic is implemented or logged; ordinary emoji uses the same one-token send cost.

## Review and release boundaries

This approved implementation is active on deployment, with no new feature flag or environment switch. Existing shared-club access activation still governs access to that product. No production deployment, configuration, indexes, rules, TTL changes, push or PR creation is performed by this work. The earlier proposal's observation/flag rollout and suggested TTL are superseded by the explicit implementation scope. Deploy both backend revisions in one coordinated release; an older writer remains a budget bypass until upgraded. Cross-app identity and same-database placement must be checked by the release owner before deployment. Android cooldown UI belongs to parent/Dirac; generic old-client errors remain usable meanwhile.

Cost: one extra guard read/write per new accepted send; guard-only write on a new violation; read but no guard write during active pauses. Replays do not read the guard. Denials do not create messages, notifications, unread stamps, operation receipts or audit entries. Event now queries at most five recent matching message documents instead of an unbounded aggregate count, explicitly ordering createdAt descending to match the existing authorUserId ASC / createdAt DESC index. Driver retains its existing bounded minute-window counter. Transactional authorization rechecks also add account/membership/block reads on the CC send paths. Transaction retries can add reads; this does not eliminate callable/read costs of denied traffic.

## Bounded verification

Run pure golden tests and the focused send-engine tests with one worker under CarCommunity's `scripts/run-no-cloud.mjs`. Transaction emulator tests require an explicitly configured loopback Firestore host and disposable `demo-chat-flood` project; importing/skipping them starts no emulator. The parent owns emulator/Android scheduling. The CarCommunity transaction suite can additionally import the Driver engine and its guard using `CHAT_FLOOD_DRIVER_ROOT` pointing at the reviewed Driver worktree; this exercises both actual backend implementations against one emulator database. No full Emulator Suite or Gradle launch is needed here. The commands below are limited to the focused files.

From the CarCommunity `functions` directory, the bounded local checks are:

```sh
node ../scripts/run-no-cloud.mjs -- ./node_modules/.bin/vitest run --maxWorkers=1 src/shared/chat-abuse-policy.test.ts src/__tests__/chat-core.test.ts src/chatchannels/chat-core.test.ts
node ../scripts/run-no-cloud.mjs -- ./node_modules/.bin/tsc --noEmit -p tsconfig.test.json
```

From the Driver `functions` directory, with `CC_ROOT` set to the reviewed CarCommunity checkout:

```sh
node "$CC_ROOT/scripts/run-no-cloud.mjs" -- ./node_modules/.bin/vitest run --maxWorkers=1 src/chat-abuse-policy.test.ts src/shared-clubs.test.ts
node "$CC_ROOT/scripts/run-no-cloud.mjs" -- ./node_modules/.bin/tsc --noEmit
```

After the parent grants an existing Firestore slot, set `FIRESTORE_EMULATOR_HOST` to that loopback host/port and `DRIVER_ROOT` to the reviewed Driver checkout. From CarCommunity `functions`:

```sh
GCLOUD_PROJECT=demo-chat-flood CHAT_FLOOD_DRIVER_ROOT="$DRIVER_ROOT" node ../scripts/run-no-cloud.mjs -- ./node_modules/.bin/vitest run --config vitest.emulator.config.ts --maxWorkers=1 src/__tests__/chat-flood.emulator.test.ts
```

Then from Driver `functions`:

```sh
GCLOUD_PROJECT=demo-chat-flood node "$CC_ROOT/scripts/run-no-cloud.mjs" -- ./node_modules/.bin/vitest run --maxWorkers=1 src/chat-flood.emulator.test.ts
```

Without both explicit emulator/project settings the transaction suites skip safely. The cross-app case additionally skips without `CHAT_FLOOD_DRIVER_ROOT`; **set it for release validation**. These suites seed isolated fixture IDs and test-only config in `demo-chat-flood`; use that dedicated project rather than another test's project. A successful unit run or skip check is not emulator validation. The unchanged product provider/RTDB access wrapper is disabled only inside that test project so the suite can exercise the transaction paths with Firestore alone; full product access, App Check and Android integration remain separate parent checks.
