# Android native redesign validation

Updated 2026-10-25. **Implementation, automated checks, rendered previews and
configured-device acceptance are separate statuses.** This is not release approval.
The current task covers Android only; iOS feature work is owned on the Mac mini.

## Current delivery: five feature areas

| Step                               | Implemented Android changes                                                                                                                                                                                                                | PR / source                                                                                                                                      |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1. Map / live / convoy             | Opaque, scrollable detail sheet with pinned close header; tools, places/search, incidents, police/sponsored details, live and convoy controls. Convoy exit actions precede the roster.                                                     | [#1178](https://github.com/SebMcCayen/carcommunity/pull/1178), `9d66d164`                                                                        |
| 2. Chat / Driver                   | Source selector, conversations, bubbles/composer, friends/DM/event-chat. Account-scoped Driver owner preserves feed/detail/scroll and reauthorizes on return; restored reads bounded to two pages.                                         | [#1180](https://github.com/SebMcCayen/carcommunity/pull/1180), `8e109413`                                                                        |
| 3. Social / events / Crown Hunt    | Social dashboard wired to existing routes, event list/detail/editor/share, Crown summaries/rewards/perks, leaderboard/KP ledger. Server-authoritative rewards and free/paid actions retained.                                              | [#1179](https://github.com/SebMcCayen/carcommunity/pull/1179), `41654fd9`                                                                        |
| 4. Garage / profile / drives       | Photo-led garage/detail/editor/picker, profile/awards hierarchy, responsive award grid, drives/detail/statistics/save-retry. Native fallback for absent photos. Compact car-named overflow menus preserve callbacks/deletion confirmation. | [#1176](https://github.com/SebMcCayen/carcommunity/pull/1176), merged; [#1182](https://github.com/SebMcCayen/carcommunity/pull/1182), `5d019ca1` |
| 5. Settings / membership / account | Grouped settings, partners/offers/application/privacy, inbox/preferences/blocks, feedback/tickets/deletion/What's new, auth/welcome/onboarding. Community/Plus/Supporter headings, existing restore flow, no hard-coded checkout price.    | [#1177](https://github.com/SebMcCayen/carcommunity/pull/1177), `fa13a39b`                                                                        |
| Integration                        | Material shape roles, semantic page headings, Social hub, scrollable Create chooser, test-only native preview and draw-fenced capture.                                                                                                     | Merged [#1181](https://github.com/SebMcCayen/carcommunity/pull/1181)                                                                             |

Combined app source: `7c2e64deecdbabd44b34dd964c2591ecced6e9d6`.
Integration PR #1181 and its five included component PRs merged on 2026-10-25
with the owner's explicit approval. Merge `8e044b41278e84aa8f8775e5a265eb4e9f5973c3`
preserves the exact tested Android tree and the newer upstream iOS fix. All
pre-merge checks passed, including iOS validation of shared generated strings.
Documentation SHAs are not tested app revisions. Original feature commits are
combined through merge commits. Upstream iOS work already on main is ancestry,
not iOS implementation by this lane. Four canonical plan strings also regenerate
the iOS String Catalog; no iOS behavior is changed here.

## Current automated and visual evidence

| Evidence                              | Status and scope                                                                                                                                                                                      |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Shell baseline `a4d11153`             | Run `40135320682`: unit/lint/debug/R8/alignment, navigation SDK and 99/99 emulator tests passed. Historical green evidence, not a still-pending wave-2 build.                                         |
| Five-area checkpoint `2f421ed1`       | Run `40187162028`: all three Android jobs passed, including 100/100 emulator cases, zero failures/skips, first attempt. Twelve native previews retained and opened. Before compact-garage correction. |
| Compact-garage integration `7c2e64de` | Run `40188008306`: debug/release unit tests, lint, debug assembly, R8/alignment, navigation SDK compilation and 100/100 emulator cases passed. Twelve final previews reviewed and retained.           |
| Local emulator                        | OS network-stack/ANR failure; contaminated captures rejected. Valid previews come from the CI emulator. Initial local warm-up builds were deliberately stopped and are not final-head evidence.       |

See [executed evidence](redesign-executed-evidence.md) for exact runs, rejected
captures and corrections. Inspect the instrumentation job separately: its
pre-existing informational status can hide failure behind a green workflow.

`Wave2RedesignCaptureTest` runs **actual production Compose views** with local
androidTest-only inputs, visibly labelled `DESIGNPREVIEW · LOKALA TESTDATA`.
No production login, Firestore write, purchase, GPS sharing or notification.
Cases: map-place, map-live, chat, driver, driver-detail, events, event-detail,
crown, garage, profile, settings, membership. These are twelve component states,
not twelve end-to-end account flows or acceptance of the full 84-view catalogue.
The fixture does not include surrounding shell chrome on every page.

CI API 34 captures are 320 × 640 physical pixels, dark, English app strings
with Swedish test content, normal text scale. This is narrow/short layout
evidence, not 390 × 844 source-equivalence. Map tiles, real photos and live Driver
account data are intentionally absent; native placeholders are not production
asset approval. See [design-qa.md](../../design-qa.md) for source comparison and
the [retained image gallery](previews/android-wave2-2026-10-25/README.md) for
unchanged PNGs with app-source/run provenance and file hashes.

### Reproduce the new native gallery

Build debug and instrumentation APKs from the same source. Install on a healthy
disposable API 34 emulator, then run:

```sh
adb -s emulator-5554 shell am instrument -w \
  -e class com.kungsbackacarcommunity.app.redesign.Wave2RedesignCaptureTest \
  -e redesignTheme dark -e redesignCaptureVariant wave2-dark \
  com.kungsbackacarcommunity.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull \
  /sdcard/Pictures/carcommunity-redesign/ /tmp/carcommunity-redesign-rendered/
```

`wave2Screen` optionally selects one case; `redesignTheme=light` selects light.
The variant argument names a folder, not locale/density/text scale. Set and
record those independently. A selected-root draw fence precedes the OS capture;
the known ANR-overlay guard must never be waived to obtain an image.

## Current acceptance backlog — not invalidated by green CI

- Populated matching states at 360/390/430 logical widths, Swedish/English,
  dark/light, large text, keyboard/insets, TalkBack and reduced motion.
- Real map/search/markers, fresh/stale GPS, explicit live start/stop/hide,
  background/foreground, convoy leader/member/ended and block/account changes.
- Real Driver linking/access/paging/expiry/logout/account switch. Reading is
  implemented; writes and an unsupported onboarding destination are not invented.
- Real Crown claim/duplicate/daily-tier budget and Stockholm midnight/DST.
- Play pending/cancel/restore/revoke/upgrade/deferred downgrade, reconciliation,
  member partner access and event attendee/check-in boundaries.
- Performance/battery/thermals on an affordable physical Android phone.

S60 crop mechanics and S83/S84 existing account/OS permission guards retain their
behavior; no newly implemented standalone screen is claimed. S81 is iOS. S82
paid cosmetics remain deferred. No new speed scoring or entitlement bypass.

The owner explicitly authorized the completed code merges after successful
checks. No version bump or Play upload was performed. The existing repository
workflow starts Firebase deployment on a main push even without backend changes;
that automation is separate from Android acceptance and no manual deployment
or cloud configuration change was initiated. Remaining device/release checks
above still apply after the merge.

## Historical first-slice report — archived, not the current to-do list

The original lane report below is retained for provenance. Its "pending", "no
APK", "no rendered captures" and "stay unmerged" wording describes that earlier
checkpoint only. Current status is above and in the executed evidence log.

Historical status: first substantial native shell slice, not release approval.
Base foundation: `41e60f05` on `1b63f152`. Android owner branch:
`codex/redesign-android`, worktree `/tmp/carcommunity-redesign-android`.
Implementation commit: `49c3df6d`. Subsequently merged foundation `91d6482d`
and `origin/main` `fdf180f1` (upstream iOS changes only) before integration CI.

## Scope and evidence

| Screens | Change                                                                                                                                                                                                   | Evidence / pending                                                                                                                                                      |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| S01-S04 | Circular search opens the real focused field on the first tap; selecting a result hides the field and keyboard; Back closes search. Semantic graphite map controls with borders; no logo or Chat circle. | Source implementation; instrumentation and reference comparison pending.                                                                                                |
| S05-S08 | Both map modes use Report / Layers / Compass / Saved places / flagged Perks. Contextual Wave and other navigation controls retained.                                                                     | MapControlSet JVM tests passed; navigation SDK compile and configured navigation pending.                                                                               |
| S13-S16 | Existing Create/Stop, live management and convoy sessions retained; driver marker accent uses semantic cyan.                                                                                             | Existing policies retained; GPS, background, stop/hide and performance device checks pending.                                                                           |
| S29-S40 | Opaque Chat tab; Chats/Driver switch; existing channels, DM, notifications and unread derivations retained. Driver uses the native adapter with UID and canonical strings.                               | Host integration requires sibling Driver files. Adapter tests/backend bridge verified by their owners; full native compile and configured feed validation pending here. |
| S53-S56 | My drives enters from the avatar menu and own Profile; Profile pushes it as a child. Post-drive saved links target MyDrives with the ride ID. Selected ride and statistics level now saveable.           | New JVM navigation tests passed. Existing drive Back tests updated; new shell restoration instrumentation pending.                                                      |
| S57-S80 | Semantic Material surface/container slots restyle existing native screens; Garage remains direct. Shared page gutters use 16dp.                                                                          | Inherited styling only; individual layouts, theme/text-scale comparison, authentication and billing device checks pending.                                              |
| S82     | Paid cosmetics                                                                                                                                                                                           | Deferred, no implementation.                                                                                                                                            |

The old `ShellTab.History` name and ordinal 1 remain reserved. Its original
rendering branch is retained for older restored state, now titled My drives;
new navigation never selects it. Keeping this compatibility branch avoids
reassigning an old identity or discarding an existing saved subtree. Chat is
appended at ordinal 5. `MyDrives` is appended to ShellRoute. History data,
subscription windows and save/retry/delete/replay operations are unchanged.

The shell still composes MapSurface.Content once. Chat selects MapCover.Opaque,
which calls setActive(false); transparent Social/Garage/search preserve the live
map. Tab selection does not start or stop the user's explicit session. Chat's
saveable section state (including drafts) is retained across the Driver switch.
The native Driver adapter owns account cancellation, paging, access and detail
Back; host code performs no Driver reads or writes.

## Automated checks executed by this lane

- `git diff --check`: passed before initial implementation commit.
- Direct Kotlin/JUnit compilation and execution of `ShellRedesignNavTest` and
  `MapControlSetTest`: **10 tests passed**, using the cached Kotlin 2.0.21 compiler
  and JUnit 4.13.2 (no Gradle or Android SDK build).
- Full Gradle compilation, Robolectric, emulator tests, lint, R8 release shrink
  and both navigation variants: **pending main's serialized integration run**.
  No compiled APK is claimed available from this lane. The host depends on the
  sibling `driverfeed` files and the shared Driver localization commit.

## Rendered capture harness (main owns emulator execution)

Existing config-less fixture: `MapFirstShellTest`. All production repositories
are null except a read-only synthetic drives fixture used by the capture test.
It uses StubMapSurface, no production auth, Mapbox token or network. This proves
native shell geometry/state, not real map tiles, assets or configured services.

After main integrates Driver files and canonical strings, build/install the
debug app and instrumentation APK in one Gradle process. Run:

```sh
adb -s emulator-5554 shell am instrument -w \
  -e class com.kungsbackacarcommunity.app.shell.MapFirstShellTest#captureRedesignShellScreens \
  -e redesignTheme dark -e redesignCaptureVariant dark-default \
  com.kungsbackacarcommunity.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull \
  /sdcard/Pictures/carcommunity-redesign/ \
  /tmp/carcommunity-redesign-rendered/
```

PNG names under `Pictures/carcommunity-redesign/<redesignCaptureVariant>/` on
API 29+. These test-only MediaStore captures survive AGP's post-test app uninstall;
no storage permission is added to the production app. Older manual test devices
fall back to app-private `files/redesign/` and must be pulled before uninstall:

- `S01-map-collapsed-search.png`
- `S02-search-expanded.png`
- `S29-chat-opaque.png`
- `S53-my-drives-fixture.png` (synthetic saved drives, never production data)

Repeat with `-e redesignTheme light -e redesignCaptureVariant light-default`.
For narrow/large-text evidence, main can set the emulator's logical width to
360dp and font scale to 1.5, rerun with `dark-360-large`, and restore the device
configuration afterwards. Width is governed by emulator size/density, not by
the capture argument. The initial 1080px / 420dpi setup is approximately 411dp.
390dp and 430dp passes, Swedish/English and both themes remain required before
claiming visual coverage. Capture arguments only select theme and output folder.

Additional focused runner classes:

```text
com.kungsbackacarcommunity.app.shell.MapFirstShellTest
com.kungsbackacarcommunity.app.navigation.NavigationSearchScreenTest
com.kungsbackacarcommunity.app.drives.DrivesRouteBackTest
```

New shell methods include `chatTab_restoresAsOpaque_andBackResumesTheSameMap`
and `myDrives_selectedDriveRestores_andBackReturnsToListThenMap` using Compose's
StateRestorationTester. Existing navigation tests assert that selecting a place
removes the input field. IME screenshots record the device's actual behavior;
focus/insets assertions do not establish that every emulator raises its IME.

Reference JPEGs are maintained by the foundation PR under
`docs/design/references/2026-10-24/`. Reference sheets inspected by this lane:
S01-S04, S29-S32, S53-S56 and S61-S64. No rendered captures have yet been
produced or visually approved by this lane. Main must pull, inspect and record
the actual artifacts and test results; a harness is not screenshot evidence.

## Remaining release verification

Both native PRs must remain unmerged until reviewed. Complete Android stub and
navigation builds, lint/R8, emulator tests, theme/locale/large-text/keyboard and
TalkBack review, then configured live/convoy/GPS/block/account, crown
claim/budget/DST, purchase lifecycle and affordable-device performance checks
listed in mobile-redesign.md. Detailed 84-view visual migration remains pending.
No deployment, store upload, production flag change or paid cosmetic purchase
has been performed or authorized by this implementation.
