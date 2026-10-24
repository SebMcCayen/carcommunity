# Native redesign executed evidence

This log separates executed checks from authored harnesses and visual acceptance.
It does not approve a release or claim that all 84 concept views are finished.

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

## Driver backend checkpoint

PR #1172's focused real callable transport tests all passed (7/7). Reader tests,
real Firestore checks and the complete grouped Firebase CI suite are separate
evidence. The full CI checks were green at backend commit `f38311e8`.
See `contracts/driver-feed/validation.md` in that PR for executed commands,
initial discovery-timeout attempts, cost-model gaps and deployment/TTL gates.

## iOS visual checkpoints

`design-qa.md` records the first theme/heading/navigation findings. The second
artifact set (`40132485529`, native code `ccf2b4e8`) confirms actual dark reading
surfaces, the Chat heading, gold primary-navigation selection and a real native
search keyboard. It also contains the Driver unavailable-account surface,
profile/history entry and Settings. These were opened with the source references.

Configured map imagery/markers and populated Driver content are not represented
by these config-less screenshots. Detailed Chat/feature layouts remain in the
migration register; passing shell checks alone does not establish full S29-S40
visual equivalence. The full Xcode test/device-Release result must be checked at
the final iOS code commit, not inferred from the existence of PNGs.
