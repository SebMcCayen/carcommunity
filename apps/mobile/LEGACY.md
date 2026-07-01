# apps/mobile — Legacy Migration Source

> ⚠️ **This directory is frozen to new product features.**

## Why this directory still exists

`apps/mobile` contains the original React Native / Expo implementation of the Kungsbacka Car Community mobile app. It remains in the repository as a **migration reference** only.

It is kept alive so that:

- Existing product behavior is fully documented and traceable.
- Feature-by-feature parity can be verified before cutover.
- The legacy build remains operational as a smoke-test baseline until the native iOS and Android apps reach verified parity.

## What changes are allowed

- **Critical security fixes** that affect the legacy build while migration is in progress.
- **Build maintenance** to keep the legacy build operational (dependency security patches, broken tooling fixes).
- **Behavior extraction** — documenting existing behavior into migration documentation or language-neutral contracts.
- **Migration-specific compatibility work** explicitly requested by a tracked migration task.

## What changes are prohibited

- New product features.
- New screens, flows, or UI components that represent new functionality.
- New API integrations or service calls that extend product capability.
- Any change that moves the migration target further from the Firebase-native architecture.

## Target replacement

The replacement for `apps/mobile` is two separate native applications:

- `apps/ios` — Swift / SwiftUI native iOS app
- `apps/android` — Kotlin / Jetpack Compose native Android app

Both native apps use Firebase Authentication, Callable Cloud Functions, Firestore, Realtime Database, and Mapbox Maps SDK.

## Migration and cutover documents

- [`docs/migration/native-firebase-migration-plan.md`](../../docs/migration/native-firebase-migration-plan.md) — phased migration plan
- [`docs/migration/feature-parity-matrix.md`](../../docs/migration/feature-parity-matrix.md) — feature-by-feature parity tracking
- [`docs/migration/cutover-checklist.md`](../../docs/migration/cutover-checklist.md) — cutover gates
- [`docs/adr/001-firebase-platform.md`](../../docs/adr/001-firebase-platform.md) — platform decision record

## Deletion gate

This directory **must not be deleted** until:

1. Both `apps/ios` and `apps/android` have verified feature parity with this implementation.
2. All cutover checklist gates in `docs/migration/cutover-checklist.md` are met.
3. Legacy deletion is explicitly approved in a separate pull request as described in the cutover checklist.
