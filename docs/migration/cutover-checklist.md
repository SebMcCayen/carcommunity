# Cutover Checklist

> **Status: the migration is complete; this document is now a hardening tracker.** `apps/mobile`
> (Expo / React Native) and `services/api` (Fastify / Prisma / PostgreSQL) were **removed from the
> repository on 2026-07-28** (PR #605); the removed code is recoverable from the `legacy-final` git
> tag. All durable data lives in Cloud Firestore — see
> [architecture.md](../architecture.md#removed-legacy-stack) for the live architecture.
>
> **Nothing below is a migration step.** The original checklist was written before cutover and was
> never maintained afterwards: every one of its 113 boxes was still unchecked on 2026-07-29, which
> read as "113 things left to do" when in fact the migration had already shipped. This revision
> resolves each item against the actual repository and the live Firebase project. What remains is
> **hardening and verification work**, plus a set of console actions that were deliberately deferred
> to the end of the MVP.

Audited 2026-07-29 against `main` @ `db260924` and the live `kungsbacka-car-community` project.

## How to read this

| Marker     | Meaning                                                                                                                           |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `- [x]`    | **Done.** Evidence cited inline — a file, a test, a workflow run, or a live resource.                                             |
| `- [ ]`    | **Outstanding.** Real work remains. Tagged `[code]` or `[console]`.                                                               |
| ~~struck~~ | **Obsolete.** The item describes the legacy stack, the descoped iOS app, or a decision that has since been settled the other way. |

"Verified live" means the production Firebase project was queried read-only on 2026-07-29.

### Tally

| Verdict                     | Count   |
| --------------------------- | ------- |
| Done                        | **50**  |
| Outstanding                 | **38**  |
| Obsolete                    | **25**  |
| Total (original item count) | **113** |

Of the 25 obsolete items, **16** are iOS (descoped from the MVP on 2026-07-02, ADR-001 amendment);
the rest are legacy-stack or superseded-decision items.

---

## Outstanding work, grouped by who acts

The 38 outstanding items collapse into a shorter list of real tasks, because several checklist rows
describe the same underlying gap. These two sections are the actionable summary; the per-part audit
below is the evidence.

### A. Code work remaining

| #   | Task                                                                                                                                                                                                                                                                                                                                                                                                                           | Checklist origin |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------- |
| C1  | **Security-rules test coverage.** 38 of ~83 `match` blocks in `firebase/firestore.rules` have no rules test. The material ones carry hand-written authorization: `conversations` + `conversations/*/messages` (mutual-block gate), `convoyChats/*/messages` (two cross-document `get()`s), `convoys`, `communityChat`, `userBlocks`, `blockVisibility`, `users/*/friends`, `friendRequests`, `userLifecycle`, `incidents`.     | Part 2.1–2.3     |
| C2  | **Missing protected-field test:** `deleted` has no client-write deny test (the other four — `role`, `activeMember`, `admin`, `suspended` — do). Behaviour is correct via the `hasOnly()` whitelist; only the test is absent.                                                                                                                                                                                                   | Part 2.4         |
| C3  | **Missing positive Storage test:** nothing asserts that an authenticated non-owner _can_ read `profileImages/`. Only the unauthenticated deny is covered.                                                                                                                                                                                                                                                                      | Part 4.3         |
| C4  | **No statement-coverage measurement exists at all.** No coverage provider is installed, no thresholds are configured, no coverage script exists. The `>80%` gate has never been measurable. (`functions/src/__tests__/coverage-guard.test.ts` is misleadingly named — it guards _callable registry completeness_, not coverage.)                                                                                               | Part 5.1         |
| C5  | **Re-enable member gating before paid launch.** `functions/src/shared/memberGating.ts` has `MEMBER_GATING_ENABLED = false`, so the `activeMember` entitlement check is bypassed repo-wide and RTDB `liveLocation/*/latest` has no `activeMember` term. Suspension and deletion still gate correctly. Flipping this requires the rule, the constant, and the tests that currently assert the open behaviour to change together. | Parts 3.2, 12.5  |
| C6  | **Suspended users cannot reach support.** `functions/src/feedback/reportIssue.ts` uses `requireActiveActor`, so a suspended member cannot file an issue. Account deletion and "hide me now" are correctly exempt; there is no terms/appeal surface at all, and `auth-completeOnboarding` rejects suspended users.                                                                                                              | Part 12.7        |
| C7  | **Kronjakt `cooldown_active` is a dead result code.** It exists in the enum, has a Swedish message and an Android mapping, but no backend path produces it. The other four anti-fraud validations (speed, stationary, geofence, impossible jump) plus position freshness are implemented _and_ boundary-tested. Either implement the cooldown or delete the dead code.                                                         | Part 12.9        |
| C8  | **Subscription expiry never revokes entitlement.** `expiresAt` is accepted and stored by `functions/src/subscription/verify.ts` but **nothing in the repo ever reads it**, and no scheduled sweep expires entitlements. A manual admin grant keeps `activeMember` forever.                                                                                                                                                     | Part 13.4        |
| C9  | **Store receipt verification has no adapter.** `subscription-verify` fails closed by design (`failed-precondition`) — the Apple/Google adapters were deliberately left until store credentials land. Blocked on S3.                                                                                                                                                                                                            | Parts 1.4, 13.2  |
| C10 | **`crownHuntSpawn` cannot be toggled from admin web.** `apps/admin/src/features/feature-flags/index.ts` builds its key list from the frozen 9-key `packages/shared` list plus one hand-added key, so the auto-spawn kill switch is not rendered. No contract-sync test guards the admin side (the functions side has one). This is the most safety-sensitive flag in the registry.                                             | Part 16.3        |
| C11 | **Partner insights is only partly flag-gated.** `partnerInsightsPassBy` gates the `anonymous_pass_by` write path only; `partnerInsights-adminSummary`, the daily aggregation, and non-pass-by interaction recording have no flag check.                                                                                                                                                                                        | Part 16.2        |
| C12 | **No App Check rejection monitoring.** No alerting policy or dashboard exists, and `errors-reportClientError` itself enforces App Check — a client failing App Check cannot report that failure through it.                                                                                                                                                                                                                    | Part 17.4        |
| C13 | **Accessibility is unverified across the board.** No `docs/accessibility.md`, no contrast values in `contracts/design-tokens/tokens.json`, no automated checks, no recorded TalkBack pass. `eslint-plugin-jsx-a11y` is registered in `apps/admin/eslint.config.mjs` but **no rules are enabled** — it is a silent no-op.                                                                                                       | Part 11.2–11.5   |
| C14 | **No release-variant gate.** CI runs `assembleDebug` / `lintDebug` only; `bundleRelease` is `workflow_dispatch`-only, and `allWarningsAsErrors` / `abortOnError` are unset. "Compiles without warnings for the release variant" has no gate.                                                                                                                                                                                   | Part 8.1         |
| C15 | **Write the rollback runbook.** Four scenarios are undocumented: Play release rollback, backend function failure, rules regression, project outage. Only `docs/app-check.md` has a real rollback section. Two rollback entries in `native-firebase-migration-plan.md` (lines 425, 783) tell the reader to revert to `services/api`, which no longer exists.                                                                    | Part 16.1        |

Smaller follow-ups surfaced by the audit, worth folding into the above: promote
`instrumented-tests (Compose UI)` from `continue-on-error: true` to required (it has been green on
every non-cancelled `main` run); correct `docs/product-decisions.md`, which still says iOS ships in
the MVP; fix the dangling `docs/migration/carcommunity-mvp-scope` link in
`.github/instructions/mobile-platform-parity.instructions.md`; remove the stale "Azure secrets"
instruction at `SECURITY.md:33`.

### B. Console / CLI actions for Seb

None of these can be done from the repository.

| #   | Action                                                                                                                                                                                                                                                                                                                                                                                   | Checklist origin      |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------- |
| S1  | **Enable the Firestore TTL policies.** Verified live: the project has **43 composite indexes deployed and zero `fieldOverrides`** — i.e. _no TTL policy exists on any collection_. Eight collection groups write an `expireAt` that nothing currently reaps. See the table below.                                                                                                        | Parts 12.12, 15       |
| S2  | **Enable Firebase Authentication sign-in providers.** Deliberately deferred to end of MVP. Until Google Sign-In is enabled in the console, the physical-device sign-in test (Part 8.8) cannot run.                                                                                                                                                                                       | Part 8.8              |
| S3  | **Google Play setup.** Play is not enabled, so: no release SHA-1/SHA-256 exists yet (needed for the API-key restriction, the Firebase Android app, and a re-download of `google-services.json`); there is no `member_monthly` product and no signed build on a track. Blocks C9, Part 13.2 and the Play half of C15. **Launch must start as a closed test group**, not a public release. | Parts 13.2, 16.1(b)   |
| S4  | **Configure Firestore backups.** Nothing exists: no scheduled export, no PITR, no IaC, no documented procedure — only an aspiration at `docs/architecture.md:249`. Then verify one restore.                                                                                                                                                                                              | Part 15.2             |
| S5  | **Configure Cloud Storage protection for `rideRoutes/`.** No bucket versioning, lifecycle, or replication config exists anywhere.                                                                                                                                                                                                                                                        | Part 15.3             |
| S6  | **Create the GCP billing budget alerts.** `docs/firebase-cost-controls.md` specifies four alerts up to the SEK 500/month ceiling; they are not yet created.                                                                                                                                                                                                                              | (not in the original) |
| S7  | **Register the stable Android App Check debug token** in the Firebase console, once. The code side is done (`AppCheckDebugSecret` + `appcheck.debugToken` in the gitignored `local.properties`); an unregistered or ephemeral token is what makes gated callables fail after every debug rebuild.                                                                                        | Part 17.1             |
| S8  | **Run the App Check monitor-only window, then flip enforcement.** `docs/app-check.md` specifies a stricter bar than this checklist ever did: ≥7 days at ≥99% valid tokens, then Firestore → RTDB → Storage. Needs S2/S3 first, since the providers are no-ops until `google-services.json` is provisioned.                                                                               | Part 17               |
| S9  | **Record owner approval of the App Check enforcement plan.** The plan is documented; the approval is not written down anywhere.                                                                                                                                                                                                                                                          | Part 17.7             |
| S10 | **Physical-device verification pass on Android** (Part 14): live-location start/stop/hide, background location, push delivery, and Mapbox rendering with real GPS.                                                                                                                                                                                                                       | Part 14               |

#### S1 detail — the eight TTL policies that do not exist

Each is a one-time
`gcloud firestore fields ttls update expireAt --collection-group=<group> --enable-ttl`. Writing an
`expireAt` field is not the same thing as having a TTL policy, and only the former is in the repo.

| Collection group          | Writer                                           | What goes unreaped without it                                   |
| ------------------------- | ------------------------------------------------ | --------------------------------------------------------------- |
| `messages`                | `chatchannels/communityChat.ts`, `convoyChat.ts` | Community (120 d) and convoy (30 d) chat messages, forever      |
| `memberSearchRateLimits`  | `users/searchMembers.ts`                         | Spent rate-limit windows                                        |
| `eventAttendance`         | `events/checkIn.ts`                              | Attendance evidence past its retention window                   |
| `pointsEconomyRateLimit`  | `points/dailyOpen.ts`, `events/checkIn.ts`       | Spent rate-limit windows                                        |
| `incidentListRateLimits`  | `incidents/listNearby.ts`                        | Spent rate-limit windows                                        |
| `incidentClearRateLimits` | `incidents/reportCleared.ts`                     | Spent rate-limit windows                                        |
| `crownCellActivity`       | `crownHunt/spawnActivity.ts`                     | Heat-map cell rows                                              |
| `recentUsers`             | `crownHunt/spawnActivity.ts`                     | Per-(cell, user) presence rows — **pseudonymous location data** |

`crownSpawnDailyClaims` (`crownHunt/claimSpawn.ts:452`) also writes an `expireAt` with no documented
command; add it to the same sweep. `recentUsers` is the one with a privacy edge —
`docs/gamification-system.md:309` already flags the retention gap.

---

## What the original checklist missed

Gaps found during the audit that had no checklist row at all:

1. **No secret-scanning tool in CI.** CodeQL (SAST) and dependency review (SCA) run; there is no
   gitleaks / trufflehog / detect-secrets. Item 12.1 asks "no secrets committed" but nothing
   continuously enforces it. The tree is currently clean — a pattern scan for GCP keys, PEM headers,
   GitHub PATs, Slack and Stripe tokens returned zero hits.
2. **No billing budget alerts.** The SEK 500/month ceiling is the hardest business constraint in
   `docs/architecture.md` and the checklist never gated on it. Now S6.
3. **No Firestore index drift gate existed at cutover time.** One has since been added
   (`.github/workflows/check-index-drift.yml`, daily) after `friend-list` broke in production on
   2026-07-19. Verified live: 43 declared, 43 deployed, **zero drift**.
4. **No production smoke check after deploy.** `deploy-firebase-functions.yml` has no post-deploy
   verification step, so item 1.5 ("health returns 200 in production") had no automated basis. It
   does return 200 — checked by hand — but nothing would notice if it stopped.
5. **No staging Firebase project** (`docs/architecture.md:240` — production only). This is why the
   "project outage" rollback scenario in C15 has no possible answer today.
6. **The feature-flag registry has no admin-side contract-sync test**, which is how C10 went
   unnoticed.
7. **`docs/product-decisions.md` — the declared source of truth — still says iOS ships in the MVP.**
   Every other document carries the 2026-07-02 descope.

---

## Part 1 — Firebase backend readiness

- [x] Emulator and unit tests pass for all Cloud Functions. Both suites are green on `main`:
      `validate-functions.yml` (lockfile drift, lint, typecheck, unit tests, build) and
      `test-firebase-rules.yml` (`pnpm run emulators:test`) — last 15 runs all `success`.
      **Correction:** the package-manager question this item deferred to "Phase 3" is settled.
      `functions` is a **pnpm** workspace (`pnpm-workspace.yaml` lists only `functions`; the root npm
      workspaces deliberately exclude it). The cited `npm run test -w @carcommunity/functions` does
      not resolve; the real command is `pnpm run test` from `functions/`.
- [x] All callable functions have at least one passing emulator integration test. **112 of 112**,
      cross-checked against `contracts/functions/functions.json`. CI-enforced, not incidental:
      `functions/src/__tests__/coverage-guard.test.ts` fails the unit suite if any implemented
      callable lacks a real emulator invocation (it deliberately ignores `describe()` titles and
      comments).
- [x] All scheduled cleanup functions are deployed and verified. **Verified live** — `functions:list`
      returns 146 deployed functions: 112 callables, **15 scheduled**, 18 Firestore triggers, 1 Auth
      trigger, 1 HTTPS. All four named cleanups are deployed (`live-cleanupExpired` every 5 min,
      `partnerInsights-cleanupExpired` daily, `notifications-cleanupExpired` daily,
      `diagnostics-cleanupExpired` monthly) and their handlers are emulator-tested directly
      (`live.emulator.test.ts:36`, `insights.emulator.test.ts:41`,
      `notifications.emulator.test.ts:41`, `diagnostics.emulator.test.ts:33`). The cron _binding_ is
      untested — the logic is verified, the schedule string is not.
- [ ] `[code]` `[console]` **Store receipt verification.** Reworded: the function is
      `subscription-verify`, not `verifySubscription`, and it is a deliberate fail-closed stub — it
      throws `failed-precondition` while no store provider is configured, and `unimplemented` if one
      were. There is no Apple or Google adapter in the repo, and its emulator test
      (`phase11.emulator.test.ts:151`) asserts exactly that. Entitlement today is granted manually by
      an admin via `subscription-grantEntitlement`. → **C9 + S3.**
- [x] `health` HTTP function returns `200` in production. **Verified live 2026-07-29:**
      `GET https://europe-west1-kungsbacka-car-community.cloudfunctions.net/health` → `HTTP 200`,
      body `{"status":"ok"}`. Handler `functions/src/health.ts`, unit-tested in `health.test.ts`.
      There is no automated smoke check — see "What the original checklist missed" #4.

## Part 2 — Firestore Security Rules

The single rules test file is `functions/src/__tests__/security-rules.emulator.test.ts` (2856 lines,
195 cases: 152 Firestore, 13 RTDB, 30 Storage) using `@firebase/rules-unit-testing`.

- [ ] `[code]` Every Firestore collection has emulator-backed Security Rules tests. **38 of ~83
      `match` blocks are untested.** → **C1.**
- [ ] `[code]` Every Firestore collection has a deny-all test for unauthenticated access. Nine
      collections have one, not 83. The catch-all
      `match /{document=**} { allow read, write: if false; }` (`firestore.rules:250`) makes the
      _behaviour_ correct; the tests do not enumerate it. → **C1.**
- [ ] `[code]` Every Firestore collection has a deny test for non-owner access to owner-only paths.
      Present for `userPrivate`, `rides`, `notifications`, `subscriptions`, RSVPs and social handles;
      absent for `userBlocks`, `blockVisibility`, `users/*/friends`, `friendRequests`,
      `conversations`, `userLifecycle`. → **C1.**
- [ ] `[code]` Every protected field has a deny test for client write attempts. Four of five are
      covered (`role`, `activeMember`, `admin`, `suspended`); **`deleted` has none.** → **C2.**
- [x] Admin-only collections deny non-admin reads and all client writes. `moderationActions/` and
      `diagnosticsReports/` are covered. **Correction:** the collection this item calls `auditLogs/`
      is actually `adminAuditEvents/`, and it is covered — including "suspended admin cannot read
      audit events". Two _other_ admin-only-read collections (`feedbackReports/`,
      `signInIssueLinks/`) are untested; folded into C1.
- [x] Firestore rules CI workflow (`test-firebase-rules.yml`) passes on `main`. Last 15 runs all
      `success`. **Correction:** this one workflow runs Firestore, RTDB _and_ Storage rules — the
      checklist's three separate "rules CI workflow" rows (2.6, 3.5, 4.5) are **one signal, not
      three**.

## Part 3 — Realtime Database Security Rules

`firebase/database.rules.json` — root deny; `liveLocation/$uid/{session,latest}`,
`liveLocationBlocks` (deny both ways), `presence/$uid`.

- [x] RTDB live location paths have emulator-backed Security Rules tests.
      `security-rules.emulator.test.ts:2399–2586`, 13 cases.
- [ ] `[code]` `liveLocation/{uid}/latest` denies reads by non-member. **Currently the opposite is
      true and asserted:** `MEMBER_GATING_ENABLED = false`, and the test at line 2519 reads
      `it('non-members CAN read while member gating is disabled')`. Unauthenticated, suspended and
      blocked readers _are_ denied. → **C5.**
- [x] `liveLocation/{uid}/latest` denies reads by a user who has blocked the owner. Enforced
      **symmetrically** — the rule checks `liveLocationBlocks/$uid/auth.uid` _and_
      `liveLocationBlocks/auth.uid/$uid`. The mirror is written from Firestore by the
      `blocking-onBlockWrite` trigger. Tests: `security-rules.emulator.test.ts:2476` (symmetric
      hiding), `:2511` (the mirror itself is neither readable nor writable), plus end-to-end coverage
      in `block-invisibility.emulator.test.ts:362`. Discovery is additionally server-filtered through
      `live-listNearby`, and `liveSessions/` is `if false` at the rules layer so no client can bypass
      that filter.
- [x] `liveLocation/{uid}/session` denies writes from any client.
      `security-rules.emulator.test.ts:2446` — "no client can write positions or sessions — not even
      the owner". The path has no `.write` rule at all, so it inherits the root deny.
- [x] RTDB rules CI workflow passes on `main`. Same workflow as Part 2.6.

## Part 4 — Cloud Storage Security Rules

- [x] Storage rules have emulator-backed tests. 30 cases across five `describe` blocks
      (`security-rules.emulator.test.ts:2588–2856`).
- [x] `rideRoutes/{uid}/` denies reads by non-owner. `:2668` and `:2675`, plus the gating/suspension
      matrix at `:2642`–`:2661` and the filename whitelist at `:2689`.
- [ ] `[code]` `profileImages/{uid}/` allows authenticated reads but only owner writes. **Two
      corrections.** `storage.rules:70` also allows `isAdmin()` to write — intentional, but "only
      owner" is wrong. And the authenticated-read _allow_ has no positive test; only the
      unauthenticated deny is covered. → **C3.** Separately worth noting: `isAdmin()` in
      `storage.rules:18` omits the `isNotSuspended()` term that `firestore.rules:31` has, so a
      suspended admin retains Storage write access.
- [x] `partnerLogos/` and `billboardImages/` allow authenticated reads but deny client writes.
      **Correction: `partnerLogos/` has never existed** in `storage.rules` — the real partner path is
      `companyImages/{companyId}/`, covered at `:2796`, alongside `offerImages/` at `:2829`.
      `billboardImages/` behaves as claimed and is tested at `:702` (in the Firestore section, which
      is why it looks uncovered). All three are authenticated-read / admin-write, so the item is
      satisfied under the correct names.
- [x] Storage rules CI workflow passes on `main`. Same workflow as Part 2.6.

## Part 5 — Cloud Functions tests

- [ ] `[code]` All domain callable functions have unit tests with >80% statement coverage. **This has
      never been measurable.** `functions/vitest.config.ts` has no `coverage` key, no
      `@vitest/coverage-*` provider is installed, there are no thresholds and no coverage script.
      Test _volume_ is high (31 unit files plus 42 emulator files), so real coverage may well exceed
      80% — nothing proves or enforces it. → **C4.**
- [x] All domain callable functions have emulator integration tests. Same 112/112 evidence as Part
      1.2.
- [x] Functions CI workflow (`validate-functions.yml`) passes on `main`. 5/5 recent runs `success`.
      It runs lockfile drift check → lint → typecheck → unit tests → build. It does **not** run
      emulator tests; those live in `test-firebase-rules.yml`.
- [x] Functions build produces no TypeScript errors. `pnpm run typecheck` (`tsc --noEmit`) and
      `pnpm run build` both pass in CI on every `main` push. **Correction:** the cited
      `npm run typecheck -w @carcommunity/functions` errors with "No workspaces found" — see Part
      1.1.

## Part 6 — Admin web Firebase integration

- [x] Admin web authenticates with Firebase Authentication (Google Sign-In).
      `apps/admin/src/lib/auth.ts` — `GoogleAuthProvider` + `signInWithPopup`; non-admins are signed
      out immediately after the popup.
- [x] Admin role is verified via custom claim on every privileged admin operation.
      `requireAdminActor` (`functions/src/admin/actorContext.ts`) is imported by 23 non-test function
      modules. `ProtectedRoute.tsx` documents itself as a UX guard only, not a security boundary.
- [x] All admin operations call callable Cloud Functions (no direct calls to `services/api`). Zero
      hits for `services/api|fastify|prisma|postgres|API_BASE` in `apps/admin/src`; all mutations go
      through `callAdmin()`. **Correction:** _reads_ are direct rules-gated Firestore SDK reads by
      deliberate design, not callables — so "all operations" is true for mutations only.
- [x] Admin web build passes. `validate-admin-web.yml` — 5/5 recent runs `success`. **Correction:**
      the cited one-liner is incomplete; `@carcommunity/shared` must be built first (its `dist/` is
      gitignored), which every workflow does.
- [x] Admin web lint and typecheck pass. Same workflow, green.
- [x] Admin web Vitest tests pass. Same workflow, green.
- [x] Admin web is accessible via Firebase Hosting URL. **Verified live 2026-07-29:**
      `https://kungsbacka-car-community.web.app/` → `200`, deep route `/users` → `200` (SPA rewrite
      working), and the CSP / HSTS / `X-Frame-Options` headers declared in `firebase.json` are all
      present on the response. This is stronger evidence than the hosting-emulator test the item
      asked for; no such emulator test exists in CI — `validate-admin-web.yml` statically validates
      the `firebase.json` hosting shape instead.

## Part 7 — iOS build and tests

**All nine items obsolete.** iOS was descoped from the MVP on 2026-07-02 — see the amendment in
[`docs/adr/001-firebase-platform.md`](../adr/001-firebase-platform.md), echoed in `README.md:21`,
`docs/architecture.md:40`, `docs/app-check.md:10` and `apps/android/README.md:3`. There is no
`apps/ios` directory, no Swift or Xcode file anywhere in the tree, and no `validate-ios.yml`. iOS is
parked on the Future Ideas board.

- ~~iOS app compiles without warnings for the release target.~~
- ~~iOS unit tests pass (XCTest / Swift Testing).~~
- ~~iOS UI tests pass for critical flows.~~
- ~~iOS CI workflow (`validate-ios.yml`) passes on `main`.~~
- ~~`GoogleService-Info.plist` is gitignored; placeholder documentation is present.~~
- ~~No hardcoded secrets, API keys, or tokens in iOS source code.~~
- ~~iOS Keychain usage is verified.~~
- ~~iOS Sign in with Apple flow completes on a physical device.~~
- ~~Firebase App Check is registered on iOS (App Attest or DeviceCheck).~~

> `docs/product-decisions.md` still describes iOS as shipping in the MVP and `docs/deployment.md:19`
> still lists `apps/ios` as a deploy target. Both contradict this decision.

## Part 8 — Android build and tests

- [ ] `[code]` Android app compiles without warnings for the release variant. CI builds
      `assembleDebug` / `lintDebug` only; `bundleRelease` is `workflow_dispatch`-only, and no
      `allWarningsAsErrors` / `abortOnError` setting exists anywhere in `apps/android`. → **C14.**
- [x] Android unit tests pass (JUnit). **185 files, 194 classes, 1936 `@Test` methods** under
      `apps/android/app/src/test/`, run by `./gradlew test` in `validate-android.yml`.
- [x] Android Compose UI tests pass for critical flows. **44 files, 264 `@Test` methods** under
      `src/androidTest/`, 42 using a Compose rule — covering sign-in, live location, events, chat,
      onboarding, account deletion, the map-first shell and Kronjakt. CI runs them on an API 34
      emulator. **Caveat:** the job is still `continue-on-error: true` despite being green on every
      non-cancelled `main` run, and `native-firebase-migration-plan.md:31` already (incorrectly)
      describes it as required. Promote it.
- [x] Android CI workflow (`validate-android.yml`) passes on `main`. The latest run is green across
      all three jobs (`validate-android`, `instrumented-tests`, `nav-variant-compile`). The
      cancellations in the run history are concurrency-group supersessions from back-to-back merges,
      not failures.
- [x] `google-services.json` is gitignored; placeholder documentation is present. Ignored in
      `.gitignore`, absent from the tree, applied conditionally in `build.gradle.kts`, documented in
      `apps/android/README.md`. There is no committed example file — the better choice for a public
      repo.
- [x] No hardcoded secrets, API keys, or tokens in Android source code. Mapbox tokens, the App Check
      debug secret and the release keystore are all injected at build time from gitignored files or
      GitHub secrets, and the release workflow `rm -f`s every decoded secret in an `if: always()`
      step. `FeatureHealthTest.kt:217` even asserts diagnostics payloads never contain `"sk."`.
- ~~Android secure token storage is verified: ID token stored via Android Keystore-backed storage.~~
  **Obsolete — and satisfying it would violate a locked decision.** There is no manual Firebase
  ID-token persistence in the app, by design: `docs/product-decisions.md` and
  `docs/auth-mobile-requirements.md:35` both state that the Firebase SDK owns token persistence and
  refresh and that native apps must **not** persist ID tokens manually. The Google ID token in
  `SignInCoordinator.kt` is transient. This item was written against the legacy custom-session model
  in `services/api`. The correct modern check — "no manual token persistence exists" — passes.
- [ ] `[console]` Android Google Sign-In flow completes successfully on a physical device. Blocked on
      the Firebase console sign-in providers being enabled. → **S2.** Repo-side prerequisites are all
      present (Credential Manager, `GoogleCredentialTokenProvider`, `SignInCoordinator`, unit tests).
- [x] Firebase App Check is registered on Android (Play Integrity). `KccApplication.kt:58` installs
      `PlayIntegrityAppCheckProviderFactory` on release and the debug provider on debug. Server side,
      every callable sets `enforceAppCheck`, pinned by `appcheck-guard.test.ts` with one vetted
      exemption (`diagnostics-submitReport`, the unauthenticated pre-auth telemetry path).
      _Registration is not enforcement_ — that is Part 17.

## Part 9 — Feature parity matrix

**All three items obsolete.** [`feature-parity-matrix.md`](./feature-parity-matrix.md) is a frozen
historical record — it says so in its own banner — and was never updated after cutover: **zero of its
~103 rows read `✅ Complete`**, and its preamble ("a feature is not complete until both iOS and
Android are marked complete") is pre-descope text that directly contradicts the 2026-07-02 decision.
Android-only _is_ the MVP. Live Android feature status is tracked in the codebase and on the GitHub
project board, not here.

- ~~Every row has `Migration status: ✅ Complete` for both iOS and Android.~~
- ~~Every feature that is N/A on a platform has a documented justification.~~
- ~~No feature is marked complete with only one platform implemented.~~

## Part 10 — Localization

- ~~Swedish localization (`sv`) is complete on iOS.~~ (no iOS app)
- ~~English localization (`en`) is complete on iOS.~~ (no iOS app)
- [x] Swedish localization (`sv`) is complete on Android. **1355 `<string>` entries** in
      `res/values/strings.xml` — note Swedish is the **default** locale; English is `values-en/`.
- [x] English localization (`en`) is complete on Android. **1355 entries**, and a sorted diff of the
      `name=` sets against Swedish is **identical** — zero missing keys in either direction. Both
      files are generated by `apps/android/scripts/generate-strings.mjs` from
      `contracts/localization/{sv,en}.json`.
- [x] Localization completeness check is part of CI. **Two independent gates.** `ci.yml` diffs the
      `sv.json` / `en.json` key paths on every PR and push and rejects empty or non-string values;
      `validate-android.yml` re-runs the generator and `git diff --exit-code`s both `strings.xml`
      files. _Soft gap:_ 76 English values are byte-identical to their Swedish counterparts — mostly
      legitimately locale-invariant, but nothing checks it.

## Part 11 — Accessibility

- ~~iOS VoiceOver: all primary flows navigable.~~ (no iOS app)
- [ ] `[code]` Android TalkBack: all primary flows navigable. No recorded pass. Implementation is
      partial but real (180 `contentDescription` uses across 39 main-source files, 82 semantics
      usages); however the 43 a11y-flavoured assertions in the instrumented tests use content
      descriptions as _selectors_, not as requirements. → **C13.**
- [ ] `[code]` Colour contrast meets WCAG AA for light and dark themes. No measurement anywhere;
      `contracts/design-tokens/tokens.json` carries no contrast or WCAG metadata; the only written
      requirement is 12 lines of unquantified prose at `docs/design-system.md:706`. → **C13.**
- [ ] `[code]` Touch target sizes meet the minimum. Only 10 combined occurrences of
      `minimumInteractiveComponentSize` / `48.dp` / `44.dp` in the whole app, and no systematic
      check. → **C13.**
- [ ] `[code]` No information conveyed by colour alone. No audit performed. → **C13.**

> The cheapest fix here: `eslint-plugin-jsx-a11y` is already installed and registered in
> `apps/admin/eslint.config.mjs` but **no rules are enabled**, unlike `reactHooks` on the line above.

## Part 12 — Security and privacy

- [x] No secrets, credentials, or tokens are committed to the repository. A pattern scan (GCP API
      keys, PEM private-key headers, `ghp_` / `github_pat_`, Stripe, Slack, `private_key_id`)
      returned **zero hits**; no `google-services.json`, keystore, `.p8`/`.p12` or service-account
      JSON is present; `.gitignore` covers `.env*`, `local.properties` and the Firebase artefacts.
      **Gap the item didn't ask for:** no secret-scanning tool runs in CI — only CodeQL and
      dependency review.
- [x] Firebase App Check production enforcement plan is documented.
      [`docs/app-check.md`](../app-check.md) covers provider provisioning, a monitor-only window,
      numeric promotion criteria, per-product rollout order, and a real rollback section.
- [x] Live location "Hide me now" removes `liveLocation/{uid}/latest`.
      `functions/src/live/session.ts:379` → `stopAndClear` deletes the Firestore discovery doc and
      calls `latestRef(uid).remove()` synchronously inside the callable, before returning — and it
      works while suspended, on purpose. Three emulator tests assert the removal
      (`live.emulator.test.ts:236`, `live-nearby.emulator.test.ts:208`,
      `block-invisibility.emulator.test.ts:385`). The wall-clock "<1 second on a physical device"
      half rolls into Part 14.
- ~~Live location TTL: sessions expire at or before 15 minutes.~~ **Obsolete — this cites the wrong
  constant, and the product changed.** Sessions are 1 h / 2 h / 4 h with a `LIVE_SESSION_MAX_MS`
  ceiling of **6 hours** (`live-core.ts:66`) and are extendable in fresh 6-hour increments. The 15
  minutes is `LATEST_STALE_MINUTES` (`live-core.ts:109`) — the staleness TTL on the _position
  marker_, which is what actually bounds how long a stale position can be seen, swept every 5 minutes
  by `live-cleanupExpired`. The privacy property the item was reaching for holds; the number
  describes a different thing.
- [ ] `[code]` Live location: non-member cannot read others' positions. Same as Part 3.2 — member
      gating is deliberately off. → **C5.**
- [x] Blocked user cannot see blocker's live location. See Part 3.3 — symmetric, three enforcement
      layers, tested at the rules layer and end-to-end.
- [ ] `[code]` Suspended user cannot access community features except support, account deletion, and
      terms. **The gating works; the exemptions don't.** `requireActiveActor` and the rules'
      `isNotSuspended()` deny suspended users correctly and are well tested, and account deletion and
      "hide me now" are correctly exempt. But **support is not** — `feedback/reportIssue.ts:61` uses
      `requireActiveActor` — and there is no terms/appeal surface at all. The rules test at `:2241`
      titled "retains access to support paths" actually asserts that the moderation-report write
      _fails_; it documents the gap rather than covering the requirement. → **C6.**
- [x] Saved drives are only stored after explicit user action. `drives-save` is a callable invoked
      only from the explicit end-of-session save prompt, and clients cannot write `rides` at the
      rules layer (`security-rules.emulator.test.ts:1902`).
- [ ] `[code]` Kronjakt anti-fraud validations pass all boundary tests. **Four of the five named,
      plus one the item didn't name, are implemented _and_ boundary-tested** — speed
      (`MAX_CLAIM_SPEED_MPS = 1.4`, tested at exactly 1.4 / 1.5 / NaN / Infinity), stationary
      collection, geofence (20–150 m, accuracy inflation capped, tested at 60 / 61 m), impossible
      jump (130 m/s), and position freshness (60 s, tested at −30 / −61 / +5 s). Risk scoring is
      boundary-tested at exactly 60. **"Cooldown" is a dead result code** with no implementation and
      therefore no test; the functional substitutes are repeat-rule windows and
      `MAX_DAILY_SUCCESSFUL_CLAIMS = 10`. → **C7.**
- [x] Partner insights: aggregation threshold of 10 unique users enforced.
      `MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD = 10` (`insights-core.ts:52`), applied as
      `Math.max(MIN, configured)` so configuration can only _raise_ it. Below threshold the counts
      are **zeroed, not merely hidden** (`resultStatus: 'insufficient_data'`), and re-zeroed again at
      read time. Tested in `insights-core.test.ts:32,41`, `insights-admin-core.test.ts:47` and
      `insights.emulator.test.ts:246`. Scope note: the threshold applies to `anonymous_pass_by` only.
- [x] Partner insights: no individual user data exposed to partner view. **True, and currently
      vacuous — there is no partner-facing view.** The only reader is `partnerInsights-adminSummary`
      behind `requireAdminActor`; raw events and aggregates are closed to _all_ clients including
      admins at the rules layer (`:643`); raw UIDs never reach storage (partner-scoped SHA hashes
      only, 7-day retention). Re-verify if a partner view is ever built.
- [x] Account deletion flow removes or anonymizes user data correctly. It **deletes**, in two stages:
      soft delete (`deleteAccount.ts` disables the Auth user and revokes refresh tokens _before_
      records commit) then `account-purgeDeleted` after 30 days. The purge covers eight document
      trees, owned `vehicles` / `rides`, cross-user mirrors (friends, friend requests, convoy
      membership, block mirror), the RTDB `liveLocation` / `presence` / `liveLocationBlocks`
      subtrees, and three Storage prefixes — all emulator-asserted (`account.emulator.test.ts:163`).
      Retentions are documented and deliberate (moderation records, audit events, crown claims).
      **Known gap, documented at `deletion-core.ts:50`:** event chat messages and RSVPs carrying
      denormalized display names are not scrubbed — a listed follow-up for the blocking domain.

## Part 13 — Subscription behavior

- ~~iOS subscription flow (StoreKit 2).~~ (no iOS app)
- [ ] `[code]` `[console]` Android subscription flow (Google Play Billing). The client half is real —
      `PlayBillingRepository.kt` drives an actual `BillingClient` and acknowledges purchases. The
      server half always throws: `subscription-verify` fails closed with no store adapter, so no
      entitlement is ever granted through a purchase, and `SubscriptionVerifier.kt` documents exactly
      that. There is also no `member_monthly` Play product and no signed build on a track. → **C9 +
      S3.**
- [x] Subscription is verified server-side, not only from client-reported state. Structurally
      guaranteed: the only writer of `activeMember` (document and custom claim) is `applyEntitlement`,
      and `subscriptions/{uid}` is backend-write-only at the rules layer
      (`security-rules.emulator.test.ts:1947`). The caveat is that the only _working_ grant path is a
      manual admin action, so there is currently nothing being verified.
- [ ] `[code]` Subscription expiry correctly revokes `activeMember` claim. **Not implemented.**
      `expiresAt` is parsed and stored but never read by anything, and no scheduled sweep expires
      entitlements — confirmed against the full 15-function `onSchedule` inventory. Manual revoke
      _does_ work correctly (fail-safe ordering; refresh tokens revoked before records commit). →
      **C8.**
- [x] Admin suspension blocks access even with active subscription. Asserted at both layers:
      `member-gating.test.ts:49,53` ("suspension overrides entitlement") and rules tests at `:442`,
      `:1553`, `:2010`, `:2189`, `:2526`.

## Part 14 — Physical device verification

All Android items roll up into **S10**; all iOS items are obsolete.

- ~~Live location session start/stop/hide tested on a physical iOS device.~~
- [ ] `[console]` Live location session start/stop/hide tested on a physical Android device — this is
      where the "<1 second" bound from Part 12.3 actually gets measured.
- ~~Background location updates verified on iOS (Core Location background mode).~~
- [ ] `[console]` Background location updates verified on Android (WorkManager / ForegroundService).
- ~~Push notifications delivered on a physical iOS device (FCM → APNs).~~
- [ ] `[console]` Push notifications delivered on a physical Android device (FCM direct).
- [ ] `[console]` Map rendering (Mapbox) verified with real GPS. Android only now, not "both
      platforms".

## Part 15 — Data integrity

- [x] No production data depends solely on the legacy `services/api` PostgreSQL database. The service
      is deleted; `docs/architecture.md:298` states "There is no relational database"; PR #605 records
      that the legacy Postgres never held production data. All live data is in Firestore.
- [ ] `[console]` Firestore backup policy is configured and at least one backup has been verified
      restorable. **Nothing exists** — no scheduled export function (checked against all 15 deployed
      schedulers), no PITR configuration, no IaC of any kind, no documented procedure. The only
      mention anywhere is an aspiration at `docs/architecture.md:249`. → **S4.**
- [ ] `[console]` Cloud Storage backup or replication is configured for ride route data. **Nothing
      exists** — no bucket versioning, lifecycle or replication config; `firebase.json` has no storage
      lifecycle block. `rideRoutes/` exists only as a rules path. → **S5.**

> These two are jointly the largest unmitigated risk on this page: production-only Firebase, no
> staging project, no backups, no restore rehearsal.

## Part 16 — Rollback procedure

- [ ] `[code]` A documented rollback procedure exists for each scenario below. Only App Check has one
      (`docs/app-check.md:89`). `docs/security.md:193` is four bullet points of principle, not a
      process. There is no `docs/runbook*.md`, `docs/incident*.md` or `docs/disaster-recovery*.md`. →
      **C15.**
  - ~~Critical bug discovered after iOS App Store submission.~~ (no iOS app)
  - [ ] `[code]` Critical bug discovered after Android Play Store submission — needs a halt-rollout /
        staged-rollout runbook. Also blocked on **S3**.
  - [ ] `[code]` Firebase backend function failure.
  - [ ] `[code]` Firestore Security Rules regression.
  - [ ] `[code]` Firebase project outage — note there is **no failover target**: production-only
        Firebase, no staging project (`docs/architecture.md:240`).
- [ ] `[code]` Feature flags are in place for all high-risk features. **Three of four.** The registry
      (`contracts/features/feature-flags.json`) has 11 flags with a single reader, `readFeatureFlag`.
      Live location (`live/session.ts`), Kronjakt (`crownHunt` plus `crownHuntSpawn`) and
      notifications (`sendPush` plus `pushTokens`) all have hard server-side kill switches, with
      emulator tests proving the callable refuses when the flag is off. **Partner insights does
      not** — `partnerInsightsPassBy` gates only the pass-by write path, while the admin summary read,
      the daily aggregation and non-pass-by recording are unflagged, and `partnerStats` is a
      client-UI toggle that does not stop the backend. (`socialSharing` and `externalDataSources`
      have no reader anywhere.) → **C11.**
- [ ] `[code]` All high-risk features can be disabled via feature flags without a code deployment.
      The toggle path itself is real and audited — `admin-setFeatureFlag` merge-writes
      `config/featureFlags` and an `adminAuditEvents` entry in one batch, and an emulator test proves
      that flipping a flag gates a domain callable. **But `crownHuntSpawn` is not rendered on the
      admin feature-flags page**, so the most safety-sensitive kill switch in the product is
      unreachable from the operator UI. → **C10.**

## Part 17 — App Check production enforcement

Enforcement is **not** currently enabled in production — the intended state, but it means every gate
below is still open.

- [ ] `[console]` App Check enforcement is not enabled in production until the following are
      confirmed:
  - [ ] `[console]` App Check debug tokens work in emulator and CI. The mechanism is implemented and
        documented (emulator enforcement disabled via `FUNCTIONS_EMULATOR`; a stable Android debug
        token via `AppCheckDebugSecret` plus the gitignored `local.properties`;
        `VITE_APPCHECK_DEBUG_TOKEN` for admin). No CI step asserts it, and the token must be
        registered once in the console — an unregistered or ephemeral token is what makes gated
        callables fail after every debug rebuild. → **S7.**
  - ~~App Attest is verified on physical iOS device.~~ (no iOS app)
  - [ ] `[console]` Play Integrity is verified on a physical Android device. The provider is
        registered but is a no-op until `google-services.json` is provisioned. → **S3 + S10.**
  - [ ] `[code]` Error monitoring is in place to detect unexpected App Check rejections. **None
        exists** — no alerting policy, no dashboard-as-code, and `errors-reportClientError` itself
        enforces App Check, so a client failing App Check cannot report it. The only App Check-aware
        signal is `appCheckPresent` on the unauthenticated `diagnostics-submitReport`. → **C12.**
  - [ ] `[console]` A monitoring window of ≥48 hours with a low App Check rejection rate is observed.
        **Superseded by a stricter bar:** `docs/app-check.md` specifies **≥7 days at ≥99% valid
        tokens** with no unexplained `unverified` spikes. Use the doc's criteria. → **S8.**
- [ ] `[console]` Production enforcement plan is documented and approved by repository owner.
      Documented ✅. The approval is **not recorded anywhere**. → **S9.**

## Part 18 — Legacy deletion approval

- [x] Legacy deletion is approved in a dedicated pull request. **PR #605**, merged 2026-07-28 — 289
      files, −78,117 lines. Recoverable at tag `legacy-final`.
- ~~Separate PR for `apps/mobile` deletion has explicit approval from repository owner.~~ **Obsolete
  — the process was consciously changed.** One combined PR was used instead of two; the earlier
  two-part attempt (PR #326) was closed unmerged. Recording the reality: #605 carried no _approving
  GitHub review_ — the owner authored and self-merged it, with approval stated as prose in the PR
  body. The code outcome is correct and CI was green; the two-PR governance requirement was not met
  and is now moot.
- ~~Separate PR for `services/api` deletion has explicit approval from repository owner.~~ (same PR)
- [x] The deletion PR confirms that all CI workflows pass after deletion. `gh pr checks 605` — **9/9
      green** (CodeQL, dependency review, contracts, ci, instrumented-tests, nav-variant-compile,
      validate-admin-web, validate-android).
- [x] `docs/product-decisions.md`, `README.md`, `docs/architecture.md`, `docs/api-guidelines.md` and
      `docs/security.md` are updated to remove legacy implementation references. All carry removal
      banners, and `docs/security.md` was already clean. **Residual defects** (small; listed under
      "Code work remaining"): `SECURITY.md:33` still says "Azure secrets";
      `native-firebase-migration-plan.md:425,783` tell the reader to roll back to `services/api`; and
      `current-state-inventory.md:43` still reads as a live "remove after…" action table for work
      that is done.
- [x] Dependabot entries for `apps/mobile`, `services/api`, and Prisma are removed.
      `.github/dependabot.yml` has exactly two entries: npm `/` and github-actions `/`.
- [x] `.github/workflows/ci.yml` legacy jobs (mobile, api, container build) are removed. Zero hits
      for `mobile|services/api|prisma|container|docker`; the file has two jobs, `ci` and `contracts`.
- [x] `services/api/Dockerfile` is deleted. No `Dockerfile*` or `.dockerignore` exists anywhere in
      the repository.

---

## Sign-off

The cutover itself is signed off by the fact of it: the legacy stack is deleted, all 146 Cloud
Functions are deployed to `kungsbacka-car-community`, the admin web is live on Firebase Hosting, and
all live data is in Firestore. The table below tracks the **hardening** state per part — these are no
longer migration gates.

| Part                                   | State                              | Blocking work   |
| -------------------------------------- | ---------------------------------- | --------------- |
| Part 1 — Firebase backend              | Done except store verification     | C9, S3          |
| Part 2 — Firestore rules               | CI green; test coverage incomplete | C1, C2          |
| Part 3 — RTDB rules                    | Done except member gating          | C5              |
| Part 4 — Storage rules                 | Done; one missing positive test    | C3              |
| Part 5 — Cloud Functions tests         | Done except coverage measurement   | C4              |
| Part 6 — Admin web                     | **Done** — verified live           | —               |
| Part 7 — iOS                           | **Obsolete** — descoped 2026-07-02 | —               |
| Part 8 — Android                       | Done except release gate + device  | C14, S2         |
| Part 9 — Feature parity matrix         | **Obsolete** — frozen record       | —               |
| Part 10 — Localization                 | **Done** — 1355/1355, CI-enforced  | —               |
| Part 11 — Accessibility                | **Not started**                    | C13             |
| Part 12 — Security and privacy         | Mostly done; three real gaps       | C5, C6, C7      |
| Part 13 — Subscription behavior        | Blocked on Play                    | C8, C9, S3      |
| Part 14 — Physical device verification | Not started (Android only)         | S10             |
| Part 15 — Data integrity               | **No backups exist**               | S4, S5          |
| Part 16 — Rollback procedure           | **No runbook exists**              | C10, C11, C15   |
| Part 17 — App Check enforcement        | Planned, not executed              | C12, S7, S8, S9 |
| Part 18 — Legacy deletion              | **Done** — PR #605, 2026-07-28     | —               |
