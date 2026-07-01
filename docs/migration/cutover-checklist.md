# Cutover Checklist

This checklist defines the mandatory gates that must be satisfied before legacy code (`apps/mobile` and `services/api`) may be deleted and before production traffic is switched to the native applications and Firebase backend.

Each item must be explicitly verified, not assumed. Legacy deletion requires a separate pull request with explicit approval from the repository owner.

---

## Part 1 — Firebase backend readiness

- [ ] Firebase Emulator tests pass for all Cloud Functions (unit tests via `npm run test -w functions` or `vitest run`, emulator tests via the `emulators:test` script in the functions workspace — the exact command depends on the package manager standardized in Phase 3 of the migration plan).
- [ ] All callable functions have at least one passing emulator integration test.
- [ ] All scheduled cleanup functions are deployed and verified (live location expiry, partner insights cleanup, notification cleanup, diagnostics cleanup).
- [ ] Subscription callable function (`verifySubscription`) is verified against Apple sandbox and Google test environment.
- [ ] `health` HTTP function returns `200` in production Firebase project.

## Part 2 — Firestore Security Rules

- [ ] Every Firestore collection has emulator-backed Security Rules tests using `@firebase/rules-unit-testing`.
- [ ] Every Firestore collection has a deny-all test for unauthenticated access.
- [ ] Every Firestore collection has a deny test for non-owner access to owner-only paths.
- [ ] Every protected field (`role`, `activeMember`, `suspended`, `deleted`, `admin`) has a deny test for client write attempts.
- [ ] Admin-only collections (`moderationActions/`, `auditLogs/`, `diagnosticsReports/`) are verified to deny non-admin reads and all client writes.
- [ ] Firestore rules CI workflow (`test-firebase-rules.yml`) passes on `main`.

## Part 3 — Realtime Database Security Rules

- [ ] RTDB live location paths have emulator-backed Security Rules tests.
- [ ] `liveLocation/{uid}/latest` denies reads by non-member (no `activeMember` claim).
- [ ] `liveLocation/{uid}/latest` denies reads by a user who has blocked the owner.
- [ ] `liveLocation/{uid}/session` denies writes from any client (callable function only via Admin SDK).
- [ ] RTDB rules CI workflow passes on `main`.

## Part 4 — Cloud Storage Security Rules

- [ ] Storage rules have emulator-backed tests.
- [ ] `rideRoutes/{uid}/` denies reads by non-owner.
- [ ] `profileImages/{uid}/` allows authenticated reads but only owner writes.
- [ ] `partnerLogos/` and `billboardImages/` allow authenticated reads but deny client writes.
- [ ] Storage rules CI workflow passes on `main`.

## Part 5 — Cloud Functions tests

- [ ] All domain callable functions have unit tests with >80% statement coverage.
- [ ] All domain callable functions have emulator integration tests.
- [ ] Functions CI workflow (`validate-functions.yml`) passes on `main`.
- [ ] Functions build produces no TypeScript errors (`npm run typecheck -w @carcommunity/functions`).

## Part 6 — Admin web Firebase integration

- [ ] Admin web authenticates with Firebase Authentication (Google Sign-In).
- [ ] Admin role is verified via custom claim on every privileged admin operation.
- [ ] All admin operations call callable Cloud Functions (no direct calls to `services/api`).
- [ ] Admin web build passes (`npm run build -w @carcommunity/admin`).
- [ ] Admin web lint and typecheck pass.
- [ ] Admin web Vitest tests pass.
- [ ] Admin web is accessible via Firebase Hosting URL (hosting emulator test).

## Part 7 — iOS build and tests

- [ ] iOS app compiles without warnings for the release target.
- [ ] iOS unit tests pass (XCTest / Swift Testing).
- [ ] iOS UI tests pass for critical flows (authentication, live location, events).
- [ ] iOS CI workflow (`validate-ios.yml`) passes on `main`.
- [ ] `GoogleService-Info.plist` is gitignored; placeholder documentation is present.
- [ ] No hardcoded secrets, API keys, or tokens in iOS source code.
- [ ] iOS Keychain usage is verified: ID token is stored in Keychain, not UserDefaults.
- [ ] iOS Sign in with Apple flow completes successfully on a physical device.
- [ ] Firebase App Check is registered on iOS (App Attest or DeviceCheck).

## Part 8 — Android build and tests

- [ ] Android app compiles without warnings for the release variant.
- [ ] Android unit tests pass (JUnit).
- [ ] Android Compose UI tests pass for critical flows.
- [ ] Android CI workflow (`validate-android.yml`) passes on `main`.
- [ ] `google-services.json` is gitignored; placeholder documentation is present.
- [ ] No hardcoded secrets, API keys, or tokens in Android source code.
- [ ] Android secure token storage is verified: ID token stored via Android Keystore-backed storage.
- [ ] Android Google Sign-In flow completes successfully on a physical device.
- [ ] Firebase App Check is registered on Android (Play Integrity).

## Part 9 — Feature parity matrix

- [ ] Every row in `docs/migration/feature-parity-matrix.md` has `Migration status: ✅ Complete` for both iOS and Android.
- [ ] Every feature that is N/A on a platform has a documented justification.
- [ ] No feature is marked complete with only one platform implemented.

## Part 10 — Localization

- [ ] Swedish localization (`sv`) is complete on iOS (all keys present in String Catalog or equivalent).
- [ ] English localization (`en`) is complete on iOS.
- [ ] Swedish localization (`sv`) is complete on Android (`res/values/strings.xml`).
- [ ] English localization (`en`) is complete on Android (`res/values-en/strings.xml`).
- [ ] Localization completeness check is part of CI (or verified manually before cutover).

## Part 11 — Accessibility

- [ ] iOS VoiceOver: all primary flows navigable with VoiceOver enabled.
- [ ] Android TalkBack: all primary flows navigable with TalkBack enabled.
- [ ] Color contrast meets WCAG AA minimum for both light and dark themes on both platforms.
- [ ] Touch target sizes meet minimum (44×44 pt on iOS; 48×48 dp on Android).
- [ ] No information conveyed by color alone (secondary indicators present).

## Part 12 — Security and privacy

- [ ] No secrets, credentials, or tokens are committed to the repository.
- [ ] Firebase App Check production enforcement plan is documented (not necessarily enabled yet; see gate below).
- [ ] Live location: "Hide me now" removes `liveLocation/{uid}/latest` within 1 second on a physical device test.
- [ ] Live location TTL: sessions expire at or before 15 minutes.
- [ ] Live location: non-member cannot read others' positions (verified via RTDB rules test and emulator).
- [ ] Blocked user cannot see blocker's live location (verified via rules test and emulator).
- [ ] Suspended user cannot access community features except support, account deletion, and terms.
- [ ] Saved drives are only stored after explicit user action (no auto-save).
- [ ] Kronjakt anti-fraud validations pass all boundary tests (speed, stationary, cooldown, geofence, impossible jump).
- [ ] Partner insights: aggregation threshold of 10 unique users enforced (verified via function test).
- [ ] Partner insights: no individual user data exposed to partner view (verified via function test and admin web review).
- [ ] Account deletion flow removes or anonymizes user data correctly.

## Part 13 — Subscription behavior

- [ ] iOS subscription flow (StoreKit 2): purchase initiates correctly; receipt verified by `verifySubscription` callable function; `activeMember` custom claim set after verification.
- [ ] Android subscription flow (Google Play Billing): purchase initiates correctly; token verified by `verifySubscription` callable function; `activeMember` custom claim set after verification.
- [ ] Subscription is verified server-side (backend callable function), not only from client-reported state.
- [ ] Subscription expiry correctly revokes `activeMember` claim.
- [ ] Admin suspension blocks access even with active subscription.

## Part 14 — Physical device verification

- [ ] Live location session start/stop/hide tested on a physical iOS device.
- [ ] Live location session start/stop/hide tested on a physical Android device.
- [ ] Background location updates verified on iOS (Core Location background mode).
- [ ] Background location updates verified on Android (WorkManager / ForegroundService).
- [ ] Push notifications delivered on a physical iOS device (FCM → APNs).
- [ ] Push notifications delivered on a physical Android device (FCM direct).
- [ ] Map rendering (Mapbox) verified on both platforms with real GPS.

## Part 15 — Data integrity

- [ ] No production data depends solely on the legacy `services/api` PostgreSQL database.
  - If production data exists in PostgreSQL, a reviewed data migration plan must be completed and verified before cutover.
- [ ] Firestore backup policy is configured and at least one backup has been verified restorable.
- [ ] Cloud Storage backup or replication is configured for ride route data.

## Part 16 — Rollback procedure

- [ ] A documented rollback procedure exists for each of the following scenarios:
  - [ ] Critical bug discovered after iOS App Store submission.
  - [ ] Critical bug discovered after Android Play Store submission.
  - [ ] Firebase backend function failure.
  - [ ] Firestore Security Rules regression.
  - [ ] Firebase project outage.
- [ ] Feature flags are in place for all high-risk features (live location, Kronjakt, partner insights, notifications).
- [ ] All high-risk features can be disabled via feature flags without a code deployment.

## Part 17 — App Check production enforcement

- [ ] App Check enforcement is not enabled in production until the following are confirmed:
  - [ ] App Check debug tokens work in emulator and CI.
  - [ ] App Attest is verified on physical iOS device.
  - [ ] Play Integrity is verified on physical Android device.
  - [ ] Error monitoring is in place to detect unexpected App Check rejections.
  - [ ] A monitoring window of ≥48 hours with low App Check rejection rate is observed.
- [ ] Production enforcement plan is documented and approved by repository owner.

## Part 18 — Legacy deletion approval

- [ ] Legacy deletion (`apps/mobile` removal and `services/api` removal) is approved in a separate pull request.
- [ ] Separate PR for `apps/mobile` deletion has explicit approval from repository owner.
- [ ] Separate PR for `services/api` deletion has explicit approval from repository owner.
- [ ] Both deletion PRs confirm that all CI workflows pass after deletion.
- [ ] `docs/product-decisions.md`, `README.md`, `docs/architecture.md`, `docs/api-guidelines.md`, and `docs/security.md` are updated to remove all legacy implementation references before or in the deletion PR.
- [ ] Dependabot entries for `apps/mobile`, `services/api`, and Prisma are removed.
- [ ] `.github/workflows/ci.yml` legacy jobs (mobile, api, container build) are removed.
- [ ] `services/api/Dockerfile` is deleted.

---

## Cutover sign-off

All checklist parts above must be explicitly checked by the responsible maintainer before production cutover.

| Part | Verified by | Date |
|---|---|---|
| Part 1 — Firebase backend | | |
| Part 2 — Firestore rules | | |
| Part 3 — RTDB rules | | |
| Part 4 — Storage rules | | |
| Part 5 — Cloud Functions tests | | |
| Part 6 — Admin web | | |
| Part 7 — iOS | | |
| Part 8 — Android | | |
| Part 9 — Feature parity matrix | | |
| Part 10 — Localization | | |
| Part 11 — Accessibility | | |
| Part 12 — Security and privacy | | |
| Part 13 — Subscription behavior | | |
| Part 14 — Physical device verification | | |
| Part 15 — Data integrity | | |
| Part 16 — Rollback procedure | | |
| Part 17 — App Check production enforcement | | |
| Part 18 — Legacy deletion approval | | |
