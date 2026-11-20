# Partner prelaunch validation

The correction PR is based on `13bf68060413885a757c8cab884aefba3eb99bcb`.
This record separates executed local checks, required CI and deferred device
acceptance. It does not authorize deployment, activation or app publication.

## Executed local checks

| Area                        | Evidence                                                                                 | Result                                                                                                   |
| --------------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Admin                       | Real-router company/offer filter navigation, history and unsaved edits; full admin suite | 52 files, 567 tests passed                                                                               |
| Hosting                     | HTTP responses through the Firebase emulator's actual Superstatic strict stack           | Root, index and deep-link HTML use no-store; hashed assets remain immutable; security headers retained   |
| Android                     | Focused JVM/Robolectric suites                                                           | 12 suites, 68 tests passed, no failures or skips                                                         |
| Android UI                  | Real emulator Compose instrumentation, including QR pixels and expired reservations      | Seven cases passed                                                                                       |
| Native Storage              | Real Android Firebase Auth/Storage SDK requests for company and billboard logos          | One case passed from tokenless initial objects                                                           |
| Backend                     | Real disposable Auth, Firestore, RTDB, Storage and Functions emulators                   | Four suites, 42 cases passed; guarded outer CLI exited zero                                              |
| Baseline regression control | Identical role/marker test file against unchanged baseline source                        | Seven expected failures reproduced: six authorization decisions and the redundant processed-event fields |

The final backend unit and structural-guard run passed 150 files / 2,976 tests.
Backend build, test typecheck, lint and deployment-lockfile synchronization also
passed.

The admin router and Hosting checks also fail against their respective original
files and pass with the corrections. Authentication/role checks and Firestore
transactions remain real in the demotion tests: the instrumentation awaits the
actual entry guard, changes the authoritative role, then returns the unchanged
authenticated actor. It does not manufacture a successful authorization result.
These six domain regressions use the existing `.run` interface with an injected
platform request context. Client token transport is exercised separately by the
SDK/HTTP workflow below; the two kinds of evidence are not interchangeable.

## Android QR correction

The owned API 34 emulator used an existing wide viewport of 2532 × 1170 pixels
at density 480. The original width-first QR constraints produced a visible
2436 × 954 crop, omitting the lower code. The bounded, centered 280 dp square
passes exact bitmap decoding on the same viewport, matching the existing iOS
size cap. The test asserts `FLAG_SECURE` before and after its owned in-process
readback; no screenshot protection was disabled.

The separate expired-status regression hides QR/reissue controls even when a
client clock would make the timestamp appear usable. Token display lifetime
continues to use its existing request-start monotonic deadline.

The Storage fixture checks the initial absence of public download tokens.
Firebase Storage Emulator 15.32.1 can mint tokens internally on GET, so this is
not a claim that the emulator object remains tokenless after reading. Production
code uses authenticated bytes, and no production request is made by these tests.

## Backend transport and lifecycle coverage

`partner-portal-transport.emulator.test.ts` signs real Firebase client SDKs into
the Auth emulator and calls the Functions emulator over HTTP. It covers:

- Unauthenticated rejection, application submission and administrator approval.
- Company-scoped access, managed offer submission and legacy administrator activation.
- Three accepted reservations, capacity rejection for a fourth customer and opaque QR issuance.
- Inspection without consumption, confirmed redemption, idempotent retry and rejected second use.
- Server maintenance publication, manager/admin company totals without customer identifiers and rejection of customer access to manager statistics.

The other real-emulator suites cover the 100-customer contention burst, concurrent
scanner attempts, rotation/expiry, cross-company authorization, authoritative
role revocation, aggregate replay and resumable account erasure. A CLI warning
about existing local ADC was retained in the execution evidence; demo-only
configuration and the inherited network guard limit service requests to loopback.
The tests do not prove production Google sign-in or device App Check attestation.
All owned emulators and listeners were stopped after completion.

The first full CI feature-group run exposed an incomplete aggregate fixture left
by the account-erasure suite. The fixture now uses the production aggregate
shape, asserts exact preservation and deletes only its owned aggregate/marker
after the suite. A fresh standard full-index emulator lifetime then passed all
11 erasure cases, explicitly verified fixture cleanup and passed the SDK/HTTP
workflow afterward. A local broad-group attempt was stopped for shared-host
memory pressure and is not counted as a pass; the full group remains a CI gate.

## CI and remaining gates

The PR must pass every registered current-head check, including full native iOS
validation. The existing iOS feature coverage includes 60 Partner-related cases
across application/QR encoding-decoding, design, models, coordinator and billboard
navigation suites. Linux did not execute Xcode locally; actual CI results belong
to the PR, not this local-results table.

The user explicitly deferred physical Android/iPhone camera, GMS, configured
native Mapbox and online/offline device acceptance. No emulator screenshot or
web fixture substitutes for those checks. The full Partner journey also retains
the separate legacy billboard/Insights consent-verification gate described in
[the prelaunch privacy review](../security/partner-portal-prelaunch-review.md).

The managed Codex Security setup validated an ordinary Git clone and the exact
original feature range, but its scan launcher still rejected the Git/HEAD target.
No scan ID or sealed report was returned. Independent source review and the
regressions above do not constitute a completed managed scan or legal certification.
