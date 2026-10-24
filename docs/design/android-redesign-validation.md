# Android native redesign validation

Status: first substantial native shell slice; implementation is not release approval.
Base foundation: `41e60f05` on `1b63f152`. Android owner branch:
`codex/redesign-android`, worktree `/tmp/carcommunity-redesign-android`.

## Scope and evidence

| Screens | Change | Evidence / pending |
| --- | --- | --- |
| S01-S04 | Circular search opens the real focused field on the first tap; selecting a result hides the field and keyboard; Back closes search. Semantic graphite map controls with borders; no logo or Chat circle. | Source implementation; instrumentation and reference comparison pending. |
| S05-S08 | Both map modes use Report / Layers / Compass / Saved places / flagged Perks. Contextual Wave and other navigation controls retained. | MapControlSet JVM tests passed; navigation SDK compile and configured navigation pending. |
| S13-S16 | Existing Create/Stop, live management and convoy sessions retained; driver marker accent uses semantic cyan. | Existing policies retained; GPS, background, stop/hide and performance device checks pending. |
| S29-S40 | Opaque Chat tab; Chats/Driver switch; existing channels, DM, notifications and unread derivations retained. Driver uses the native adapter with UID and canonical strings. | Host integration requires sibling Driver files. Adapter tests/backend bridge verified by their owners; full native compile and configured feed validation pending here. |
| S53-S56 | My drives enters from the avatar menu and own Profile; Profile pushes it as a child. Post-drive saved links target MyDrives with the ride ID. Selected ride and statistics level now saveable. | New JVM navigation tests passed. Existing drive Back tests updated; new shell restoration instrumentation pending. |
| S57-S80 | Semantic Material surface/container slots restyle existing native screens; Garage remains direct. Shared page gutters use 16dp. | Inherited styling only; individual layouts, theme/text-scale comparison, authentication and billing device checks pending. |
| S82 | Paid cosmetics | Deferred, no implementation. |

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
  /sdcard/Android/data/com.kungsbackacarcommunity.app/files/redesign/ \
  /tmp/carcommunity-redesign-rendered/
```

PNG names under `files/redesign/<redesignCaptureVariant>/`:

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
