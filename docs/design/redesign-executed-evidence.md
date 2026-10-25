# Native redesign executed evidence

This log separates executed checks from authored harnesses and visual acceptance.
It does not approve a release or claim that all 84 concept views are finished.

## Current Android wave 2 — 2026-10-25

Five feature areas and the combined preview are described in
[Android validation](android-redesign-validation.md). Current app source is
`7c2e64deecdbabd44b34dd964c2591ecced6e9d6`; documentation SHAs are not app-build
evidence. Final run: [40188008306](https://github.com/SebMcCayen/carcommunity/actions/runs/40188008306).
All three Android jobs passed at that exact head: debug/release unit tests,
lint, debug assembly, R8 release shrinking, 16 KB alignment (10 native libraries),
real navigation-SDK compilation and **100/100** API 34 Compose emulator cases
(zero failures/skips, first attempt). CI/contracts, CodeQL and dependency review
also passed. The separate generated-string iOS workflow completed successfully
as well; it is no longer pending. All PR checks were green before the owner-
authorized merge of integration #1181 and its five included component PRs.
Merge `8e044b41278e84aa8f8775e5a265eb4e9f5973c3` retains the tested Android tree
and preserves the upstream iOS local-transport fix. Documentation does not
replace the source/run provenance above; configured-device acceptance remains
separate from merge approval.

The local serialized checkpoint build completed in 14m30s: debug app/test APKs
and 3,586 unit tests, zero failures/errors, one skipped case. Because that build
started before the garage merge, it is checkpoint evidence only. An incremental
build at final app source `7c2e64de` then assembled both APKs and passed all
108 garage unit tests (10 suites, zero failures/errors/skips) in 5m21s. The
full final debug/release suite, lint and release gates are established by CI,
not inferred from the targeted local run.

### Final native recapture

At `7c2e64de`, the emulator job and real navigation-SDK compilation passed.
Artifact `android-redesign-captures` (`12597037490`) was downloaded from attempt
1 of run `40188008306`. All twelve new PNGs were opened and checked against
their intended states. The corrected garage shows both local cars and Add
vehicle in the short viewport, with no management-button stack. The S57
density finding is resolved in this captured state, not claimed for every
viewport/locale. The other eleven captures retain their intended content.

The [retained gallery](previews/android-wave2-2026-10-25/README.md) includes
unchanged PNGs and a SHA-256 manifest; CI's temporary retention does not erase
this review evidence. The separate `validate-android` job passed R8 and native
alignment as well; that result was checked independently of the PNGs.

### Executed checkpoint before compact garage

Run [40187162028](https://github.com/SebMcCayen/carcommunity/actions/runs/40187162028)
at `2f421ed168105e1d69244f556f06bc3205d6dbf8` passed each Android job:
debug/release unit tests, lint, debug assembly, release R8/16 KB alignment,
real navigation-SDK compilation, and all **100/100** API 34 Compose UI cases
(zero failed/skipped, first attempt). Artifact `android-redesign-captures`
(`12597070521`) contains twelve 320 × 640 English/dark, normal-text native
previews with local test data. Every image was opened and checked against its
filename. This run precedes the S57 density correction.

### Failures, corrections and rejected evidence

- Chat's first CI compilation rejected an invalid top-level test import;
  `8e109413` removes it, retaining the real node assertion.
- A convoy regression exposed Leave below a long roster. Production actions
  move before the roster (`9d66d164`); role/callback/confirmation assertions remain.
- Partner-upgrade and Supporter-downgrade tests now scroll to reachable controls
  (`fa13a39b`). Free users still cannot see codes; downgrade assertions remain.
- Run `40186384885` passed UI tests but some PNGs captured the previous window
  (Chat showed Live; Settings showed Profile). Those PNGs are **rejected visual
  evidence**. A Compose draw fence plus compositor settling fixed timing;
  `40187162028` was recaptured and opened, not relabelled from the failed set.
- Source/native S57 review found excessive card height from the management
  button stack. PR #1182 (`5d019ca1`) uses 2.5:1 photos, 8 dp gaps and an
  accessible car-named overflow menu; callbacks/deletion confirmation remain.
  Its final capture is separate from the pre-correction garage screenshot.
- The local read-only API 34 emulator suffered OS network-stack/ANR failures.
  Its preview was rejected by the guard, not reported as app QA. CI provides
  valid previews; failed local captures are not included.

### Current limitations

Production views use test-only inputs, not live Firebase or a real purchase.
Map imagery/photos, full-catalogue states, locale/theme/large-text matrix,
physical-device performance and configured-service checks remain unverified.
Use the current checklist, not historical "pending" wording below. No new store
release or production configuration is performed by this Android delivery.

## Historical wave-1 evidence — retained, not current pending status

The following sections describe earlier app revisions. Their time-local
"pending", "not deployed" and "no merge" statements are historical, not claims
about today's repository/production state. Use the current Android checklist
above for wave 2. iOS history is preserved; current iOS work is owned elsewhere.

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

### Final corrected iOS code

Run `40138657535` at **`197b8970`** passed 1,201 XCTest unit tests, 17 Swift
Testing tests and all **nine UI tests**, with zero failures, followed by a
successful unsigned generic-device Release build. Fresh source/native image
comparison confirmed compact navigation, contained search/menu glyphs, full
enlarged recovery text and horizontally reachable Notifications. The new UI
test independently checks minimum hit-target geometry, selection, complete
text height and scrolling, rather than existence alone. Intermediate run
`40137029824` failed the 44-point target assertion; the assertion was retained
and the production content shape fixed, not weakened to produce a pass.

Foundation code/test checkpoint `38e05c53` also passed every PR check, including
both native validation workflows, after the canonical Social label correction.
This log's final update changes documentation only. Android source `a4d11153`
and iOS source `197b8970` are the explicitly verified app revisions; do not
substitute a documentation commit's SHA for a tested app revision.

Configured map imagery/markers and populated Driver content are not represented
by these config-less screenshots. Detailed Chat/feature layouts remain in the
migration register; passing shell checks alone does not establish full S29-S40
visual equivalence. The final Xcode test/device-Release result above was checked
independently, not inferred from the existence of PNGs. Real configured map,
GPS/live/convoy, original Driver-account/data transitions, purchases, affordable
device performance and the remaining detailed layouts still require validation
before a production release. No physical-device or full-84-view pass is claimed.
