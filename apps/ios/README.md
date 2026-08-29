# KCC iOS app

The native iOS client (Swift / SwiftUI), in scope per
[ADR-002](../../docs/adr/002-ios-app-rescope.md) and governed by the
[mobile platform parity instructions](../../.github/instructions/mobile-platform-parity.instructions.md).

## Requirements

- Xcode 26+ with the iOS platform installed
- No Apple Developer membership needed for simulator work (see ADR-002 for the
  production milestones that do need one)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) only when changing
  `project.yml` (the generated `KCC.xcodeproj` is committed)

## Project layout

| Path | Purpose |
| --- | --- |
| `project.yml` | XcodeGen spec — the source of truth for `KCC.xcodeproj`. After editing, run `xcodegen generate` from this directory and commit both. |
| `KCC/` | App sources. `Shell/ShellNav.swift` is the pure navigation state machine ported from Android's `ShellNav.kt`; the two must stay in sync. |
| `KCCTests/` | Unit tests (XCTest). `ShellNavTests.swift` mirrors Android's `ShellNavTest.kt`. |
| `scripts/` | Contract codegen (siblings of `apps/android/scripts`): `generate-strings.mjs` → `KCC/Resources/Localizable.xcstrings`, `generate-tokens.mjs` → `KCC/Design/Tokens.swift`. Both outputs are committed and drift-checked by `validate-ios.yml`. |

## Firebase configuration (config-less builds)

`KCC/GoogleService-Info.plist` is **gitignored** and injected at build/release
time — exactly like Android's `google-services.json`:

- Without the file, the app still builds, launches, and renders; Firebase
  stands down (`FirebaseBootstrap.isConfigured == false`) and every
  Firebase-backed repository factory returns nil instead of crashing. CI
  builds this way.
- With the file present at `KCC/GoogleService-Info.plist`, a post-build script
  embeds it in the app bundle and Firebase configures at launch.

Never commit a real `GoogleService-Info.plist`.

## Building and testing (CLI)

```bash
# From apps/ios — build for the simulator
xcodebuild -project KCC.xcodeproj -scheme KCC \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build

# Run the unit tests
xcodebuild -project KCC.xcodeproj -scheme KCC \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
```

## Developing against the Firebase emulators

There is no paid Apple Developer membership yet (ADR-002), so the Sign in
with Apple capability cannot be used against production. Development instead
targets the **Firebase Auth emulator**, which never verifies token
signatures:

1. Start the emulators from the repo root: `firebase emulators:start`
   (auth listens on 127.0.0.1:9099 per `firebase.json`).
2. Provide any `GoogleService-Info.plist` at `KCC/GoogleService-Info.plist`
   (values are not validated by the emulator).
3. Run the app with the environment variable
   `FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099` (Xcode scheme → Run →
   Arguments → Environment Variables).

In DEBUG builds with that variable set, sign-in uses
`EmulatorAppleIDTokenProvider` — a fabricated (unsigned) Apple identity token
that exercises the real `OAuthProvider.appleCredential` exchange path against
the emulator. Release builds always use the real
`ASAuthorizationController` flow.

## Localization

Swedish is the source language, English is required alongside it. Keys are the
same semantic names as `contracts/localization` (e.g. `shell.tabMap`).
`Localizable.xcstrings` is **generated** — edit the contracts, then run
`node apps/ios/scripts/generate-strings.mjs` from the repo root (CI fails on
drift). Do not edit the catalog by hand, and do not hard-code user-facing text
in views.

## Parity

When changing shell behaviour here, port the change (and its tests) to
`apps/android/.../shell/ShellNav.kt`, and vice versa. Intentional differences
must be documented per the parity instructions — currently: Sign in with Apple
only, and no turn-by-turn navigation in v1 (both recorded in ADR-002).
