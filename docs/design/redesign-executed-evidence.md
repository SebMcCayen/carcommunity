# Native redesign executed evidence

This log separates executed checks from authored harnesses and visual acceptance.
It does not approve a release or claim that all 84 concept views are finished.

## Foundation contract regression

The foundation's iOS smoke test still expected Swedish `Socialt`, while the
approved canonical label is `Social`. Run `40135549312` failed that exact
hard-coded-label assertion after the string regeneration. The fixture now
expects the canonical label; native redesign tests use the new Chat-based
navigation independently. This is a test expectation correction, not a hidden
claim that a failed run passed.

## Android local checkpoint

The initial config-less app and instrumentation APKs were built together with
`:app:testDebugUnitTest`. Gradle completed successfully in 35m49s on the local
Linux host. JUnit XML reports 3,554 total tests, zero failures/errors and one
skipped case. The APKs' SHA-256 values were:

- app: `de53b9c87ce3f09ffa58e31848886665eb0d21494d1815a68a94b616d1ef6a37`
- instrumentation: `27667035d744bd6fdb64d7014f870ac729c7e5487fe515bae888a5e80e1baeba`

These are an earlier code checkpoint, **not** final-HEAD visual or release
artifacts. Subsequent heading, navigation-role, map-rail and test-harness fixes
must be verified through their own full CI runs.

The API 34 emulator was resized to 1024 x 2342 pixels at 420 dpi (approximately
390 logical pixels wide) and the app locale set to Swedish. First S01/S02
captures were unusable for visual acceptance: an unrelated Pixel Launcher ANR
dialog dimmed and covered the app. The dialog was dismissed using UI-tree bounds.
A test capture guard now rejects that known OS overlay instead of presenting
its pixels as successful design evidence.

Instrumentation also exposed obsolete icon-description matchers after visible
navigation labels were added. Material's merged semantics exposes the labelled
clickable tab rather than the redundant icon description. Tests now select that
actual tab without adding duplicate accessibility speech to production controls.

AGP removes app-private capture files when it uninstalls the test app. Test-only
MediaStore captures on API 29+ and CI collection from
`Pictures/carcommunity-redesign` retain evidence without production storage
permissions. This follows the [Android own-media storage guidance](https://developer.android.com/training/data-storage/shared/media).

CI run `40135320682` at Android code `a4d11153` passed all 99 Compose
instrumentation cases with no skipped/failed cases, on API 34. The navigation
SDK compilation/alignment job also passed. Its retained S01/S02/S29/S53 images
were opened alongside the source sheets: upper-right map tools, collapsed
search, the opaque Chat host and My drives are present. This narrow English
config-less fixture does not prove Swedish, large-text or populated map/feed
fidelity. Lint and release shrinking have separate job results; do not infer
them from the instrumentation pass.

The completed run also passed `validate-android`: debug/release unit tests,
Android lint, debug assembly, R8 release shrinking and 16 KB native-library
alignment. All three Android jobs were checked individually, because the
repository's pre-existing instrumentation job is informational/non-blocking;
an overall workflow conclusion alone can conceal an instrumentation failure.

## Driver backend checkpoint

PR #1172's focused real callable transport tests all passed (7/7). Reader tests,
real Firestore checks and the complete grouped Firebase CI suite are separate
evidence. The full CI checks were green at backend commit `f38311e8`.
See `contracts/driver-feed/validation.md` in that PR for executed commands,
initial discovery-timeout attempts, cost-model gaps and deployment/TTL gates.

The existing `deploy-firebase-functions.yml` runs on pushes to `main`.
Merging the backend PR can therefore deploy automatically, subject to the
production environment's protections. Verify the TTL/App Check and rollout
prerequisites **before merging**, not after. This task performed no merge,
deployment, production configuration change or store release.

## iOS visual checkpoints

`design-qa.md` records the first theme/heading/navigation findings. The second
artifact set (`40132485529`, native code `ccf2b4e8`) confirms actual dark reading
surfaces, the Chat heading, gold primary-navigation selection and a real native
search keyboard. It also contains the Driver unavailable-account surface,
profile/history entry and Settings. These were opened with the source references.

Run `40134626295` at `0bac8e00` passed 1,201 XCTest unit tests, 17 Swift Testing
tests, all eight UI tests and the unsigned generic-device Release build. Its
final PNGs confirm the corrected tool-rail placement and actual theme variants.
The largest-text Chat image nevertheless exposed a responsive layout failure,
recorded in `design-qa.md`; a subsequent fix and verification are required.

Configured map imagery/markers and populated Driver content are not represented
by these config-less screenshots. Detailed Chat/feature layouts remain in the
migration register; passing shell checks alone does not establish full S29-S40
visual equivalence. The full Xcode test/device-Release result must be checked at
the final iOS code commit, not inferred from the existence of PNGs.
