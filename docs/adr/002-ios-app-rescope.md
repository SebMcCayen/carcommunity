# ADR-002: Bring the iOS app into scope

## Status

Accepted

## Date

2026-08-28

## Context

ADR-001 (amendment 2026-07-02) descoped the iOS app from the MVP: the MVP shipped Android +
admin web + backend only, with iOS parked on the Future Ideas board. The amendment committed to
keeping the language-neutral `contracts/` layer iOS-ready so parity could be restored without
rework.

That MVP has since shipped: the Android app is in Google Play closed testing (vc47 / 0.8.36),
the backend exposes 141 documented callables in `contracts/functions/functions.json`, and the
contracts layer carries schemas, localization (sv/en), design tokens, and feature flags that no
platform-specific code depends on. The precondition for restoring iOS — a stable, contract-driven
backend — is now met.

Development begins without a paid Apple Developer Program membership. Simulator builds, XCTest,
and the Firebase Emulator Suite (including emulated Sign in with Apple flows) require no
membership; production sign-in, push notifications (APNs), App Attest, physical-device
distribution, and TestFlight/App Store do.

## Decision

The iOS app (`apps/ios`) is back in scope and will be built incrementally toward the parity
target defined in `.github/instructions/mobile-platform-parity.instructions.md`.

### Technology

As already mandated by the parity instructions: Swift, SwiftUI, Swift Concurrency, Swift Package
Manager, XCTest/Swift Testing, Sign in with Apple, StoreKit 2, Core Location, UserNotifications,
Keychain, Mapbox Maps SDK for iOS. No cross-platform mobile frameworks.

### Architecture

The iOS app mirrors the Android app's architectural idioms natively rather than inventing new
ones:

- Repository protocols with Firebase-backed implementations, constructed through null-safe
  factories so that a build without `GoogleService-Info.plist` still compiles and renders
  (the Android `createIfAvailable` pattern; `GoogleService-Info.plist` is gitignored and
  injected from CI secrets, exactly like `google-services.json`).
- Plain-Swift coordinator classes owning observable state, unit-testable with fake
  repositories (the Android `Coordinator` + `MutableStateFlow` pattern).
- A pure-Swift navigation state machine ported from `ShellNav.kt` (tabs, route stack, map
  cover), not a port of `AuthenticatedApp.kt`.
- Contracts consumed via codegen, drift-checked in CI: localization → String Catalog,
  design tokens → Swift, vehicle catalogue → Swift, as siblings of the existing Android
  generators.

### Authentication

Reaffirmed from ADR-001 and the parity instructions: iOS uses **Sign in with Apple only**, with
no provider account linking. An existing Android member who signs into iOS with an Apple account
receives a new, separate account (different `uid`). This is accepted for the current user scale;
provider linking remains a possible future decision and is not blocked by anything in this ADR.

Until a paid Apple Developer membership exists, Sign in with Apple is developed and tested
exclusively against the Firebase Auth emulator, which fakes the OAuth flow without the
entitlement.

### Turn-by-turn navigation

The first iOS version ships **map display only** (Mapbox Maps SDK for iOS). It does not include
the Mapbox Navigation SDK or turn-by-turn navigation.

The parity instructions state "Do not add Mapbox Navigation SDK or turn-by-turn navigation
unless an approved architecture decision authorizes it." Android has since shipped turn-by-turn
behind a token-gated source-set swap (`app/src/nav` vs `app/src/noNav`, surfaced as
`BuildConfig.NAV_SDK_ENABLED`). This ADR:

1. Retroactively records the Android turn-by-turn implementation as an approved,
   **temporary** platform difference.
2. Defers the iOS equivalent until the core app reaches parity on the non-navigation surface.
   When added, it must follow the same pattern: token-gated, stub fallback, compiling without
   the Mapbox Downloads token.

### Milestones gated on the Apple Developer Program membership

The following are explicitly out of scope until a paid membership exists, and constitute the
"production cutover" milestone for iOS:

- Sign in with Apple against production Firebase (capability requires a paid team).
- Push notifications (APNs certificates/keys, FCM on iOS).
- App Check via App Attest (`docs/app-check.md` already lists this as descoped with iOS).
- StoreKit 2 against App Store Connect, and the corresponding Apple receipt/transaction
  verification path in `subscription.verify` (the backend currently has Google Play
  verification only).
- Physical-device distribution, TestFlight, and App Store submission (no App Store metadata
  exists yet; `docs/play/` has no Apple equivalent).

### Member gating

`MEMBER_GATING_ENABLED`-style entitlement gating is currently disabled repo-wide across five
unsynchronized switches (see `functions/src/shared/memberGating.ts`). The iOS app must
implement the same switch pattern as `config/MemberGating.kt` on Android so that re-locking
remains a five-place flip, with iOS added as a sixth documented location.

## Consequences

### Positive

- The parity requirement stops being aspirational; divergences (turn-by-turn, auth providers)
  are now recorded and bounded instead of implicit.
- The contracts layer gets its second native consumer, validating the codegen pattern.
- Development can proceed immediately at zero Apple cost using the simulator and emulators.

### Negative

- Every mobile feature change now carries real dual-platform cost, as the parity checklist
  always intended.
- Android members cannot reach their existing account on iOS until a linking decision is made.
- CI needs a macOS runner lane for iOS validation, which is slower and more expensive than the
  Linux lanes.

### Neutral

- The Android app and backend are unchanged by this decision.
- App Store metadata, privacy documentation, and membership purchase are deferred to the
  production cutover milestone.
