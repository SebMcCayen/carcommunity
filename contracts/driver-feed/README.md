# Native Driver read integration

The canonical backend contract is [read-api.md](read-api.md), with the
[machine-readable schema](../schemas/driver-feed.schema.json). The implementation
exports `driverFeed-list` and `driverFeed-detail` in `europe-west1` from
CarCommunity's default Functions codebase. **Backend deployment has not been
performed.** Main owns review, PRs, rollout and production verification.
Before deployment, verify Firestore TTL is **ENABLED** on
`driverRateLimits.expiresAt`. The new UID-linked marker becomes eligible for TTL
deletion two hours after its last read; expiry metadata alone does not delete it. Driver's existing
UID-based deletion worker also covers it. See the retention details in the API
contract; production TTL state has not been verified by this change.

Each page requires one callable, scans at most 20 candidates, and shares a
120-page/hour per-UID budget. A filtered empty page may have a continuation
cursor. Feed and comment cursors are independent and retain timestamp
nanoseconds plus the document ID. There is no per-post hydration callable or
direct Firestore content fallback.

Private Driver and canonical KCC block decisions are computed server-side for
every page and every distinct author, including both directions. Detail
reauthorizes its parent on every reply page within a consistent read snapshot.
Later private block changes are reevaluated on the next page/refresh; the server
does not expose private block lists or add client listeners to another user's
private block records. See the contract's release boundary for the broader
existing direct-rule surface.

Driver onboarding is never automatic. Reading requires existing canonical and
Driver onboarding; the bridge never creates profiles, accepts terms, or calls
`driver-completeProfile`. KCC `admin` does not become Driver staff, and this read
surface grants no staff bypass. Native account-change cancellation, generation
fencing and clearing cached content remain native responsibilities.

## Native mounting handoff

The parallel native implementation supplies these host interfaces:

- Android: `DriverFeedRoute(uid, DriverFeedStrings(lookup), modifier, isActive = true, repository?)`
  in `com.kungsbackacarcommunity.app.driverfeed`.
- iOS: `DriverFeedScreen(uid:strings:isActive:repository:)` (`isActive` defaults to true), with
  `DriverFeedStrings(lookup:)`.

Both own post-detail navigation and use the existing Firebase app, Auth UID and
App Check. Hosts provide canonical localized strings and mount the feed in the
chat surface; the feed contributes no fabricated unread count. The native lane
owns its source/tests, and main owns mounting/localization. On iOS regenerate
the Xcode project from `project.yml` to include the new native source/test folders.
Hosts pass `isActive = false` while the feed is covered or its tab is hidden.
The components clear content and cancel listeners/tasks, retain the selected
detail ID, and reauthorize on resume. UID changes recreate state; generation and
captured Firebase-user fencing reject late responses. Owner/parent invalidations
are coalesced over 150 milliseconds.

The native lane supplies Android model/coordinator/wire tests and matching iOS
XCTest sources. Run the Android tests through the actual application classpath:

```sh
cd apps/android
./gradlew :app:testDebugUnitTest --tests 'com.kungsbackacarcommunity.app.driverfeed.DriverFeedTest'
```

This does not replace full native builds or device checks. iOS XCTest execution
requires an Apple toolchain. A temporary cached-classpath compiler helper used
during development is not part of the delivery.

## Focused backend validation

Use existing dependencies where available:

```sh
cd functions
pnpm exec vitest run src/driverFeed/reader.test.ts src/__tests__/appcheck-guard.test.ts src/__tests__/coverage-guard.test.ts src/__tests__/max-instances-guard.test.ts
pnpm run typecheck
pnpm run lint
pnpm run build
```

The emulator integration file is
`functions/src/__tests__/driver-feed.emulator.test.ts`; it requires local demo
Auth, Functions and Firestore emulators and never contacts production. Native
builds/device validation and full Functions CI after push remain separate checks.
