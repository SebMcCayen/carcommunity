# Android native redesign validation

Updated 2026-10-26. **Implementation, automated checks, rendered previews and
configured-device acceptance are separate statuses.** This is not release approval.
The current task covers Android only; iOS feature work is owned on the Mac mini.

## Pass-3 source audit — 2026-10-26

This documentation-only audit inspected the reference JPEG sheets and native
source in the independent pass-3 worktrees. It ran **no builds, Gradle/JUnit,
emulator/device sessions, screenshots, purchases, production calls or deployments**.
It does not establish a visual pass, a release gate or a combined app revision.
The authoritative point-in-time [S01–S84 matrix](mobile-redesign-screens.md#per-screen-android-matrix)
lists the exact source files, reference page, lane/commit and remaining evidence
for every state. It covers 84/84 source/reference mappings: **82 Android states
runtime/visual-unverified, one iOS-only state (S81), one deferred cosmetic state
(S82)**. Reviewed/changed source must not be counted as rendered acceptance.

### Source reviewed, implementation present, execution pending

The matrix's snapshot table pins committed chat, Crown, event, garage/profile
and settings source against base `345a9f11693374d1e52a88f3ed4c20c1bc67edbd`.
Main's `map-shell` changes are explicitly uncommitted WIP, fingerprinted there.
No sibling changes were copied or merged by this audit. The garage/profile
snapshot is `5979d4dcfee274d0d7d80842e82f04525ceaa704`; subsequent real-cover and
responsive-ratio work must be identified by its own source commit and captures.

| Area | Source finding | New evidence status |
| --- | --- | --- |
| Map/shared shell | WIP `AuthenticatedApp.kt`, `shell/MapHome.kt`, `shell/MapDetailSheet.kt`, `shell/DockedMapSheetHost.kt`, `shell/MapboxMapSurface.kt`, `map/BrowsingInitialCamera.kt`, `incidents/IncidentUi.kt`: dock to the actual body/footer bounds and preserve/restore browsing camera. | Source reviewed only. Main owns integration, restoration tests and real map/sheet captures. |
| Chat | Compact source/channel strips, native conversation context, real friend picker/search, responsive friend rows, selected-message actions. Existing original Driver read path remains account-scoped. | New/updated tests and local fixtures exist; compilation, interaction execution and matched captures are pending. |
| Crown / KP | Native rarity assets, overview/statistics/history/shop organization, shared docked details/deploy controls and ledger filters/month groups. | Source tests exist; shared `MapDetailSheet` integration plus actual flag/account/claim/duplicate/budget/DST evidence pending. |
| Events | Native date badges and schedules, grouped detail actions/check-in, date/time editor, real event chat title, group-drive context and single-friend share teaser. | Source tests/fixtures exist. Event lane's capture dispatch was edited locally; no capture execution is inferred. |
| Garage/profile/drives | Real fact hierarchy, gallery controls, month headings, responsive metrics, award dates, S63 relationship actions/carousel. Lifetime maximum-speed row introduced during pass 3 was removed again. | Eleven original added regression methods plus four S63 methods and strengthened existing assertions are source-reviewed, not executed here. |
| Settings/partners/account | Real partner address/count, responsive notification channel columns, truthful block fallback/date, deletion acknowledgement/confirmation, release timeline. | Lane handoff records generators/static checks only. Auth, Play, permissions and configured account transitions remain unverified. |

Paths in the first table row are relative to
`apps/android/app/src/main/java/com/kungsbackacarcommunity/app/`.
The per-screen register supplies the exact feature paths for every other row.

### Native fixture inventory is not capture evidence

All paths below are repository-relative test-only entry points, with local data.
Main owns captions, a single merged dispatch, healthy emulator, viewport, theme,
locale, font scale, IME and capture files. A composable or test file does not prove
that it compiled, ran, rendered or matched the reference.

- `apps/android/app/src/androidTest/java/com/kungsbackacarcommunity/app/redesign/Wave2GaragePreview.kt`: `garage`, `profile`, `vehicle-detail`, `vehicle-editor`, `awards`, `member-profile`, `drive-history`, `drive-detail`, `drive-stats`, `drive-save-failed`. Extra cases require main's dispatch integration.
- `apps/android/app/src/androidTest/java/com/kungsbackacarcommunity/app/redesign/EventDesignPreview.kt`: `events`, `event-detail`, `event-detail-paid`, `event-detail-creator`, `event-check-in`, `event-create`, `event-edit`, `event-saving`, `event-chat`, `event-chat-restricted`, `event-group-drive`, `event-share`. The event lane's `Wave2RedesignCaptureTest.kt` dispatch exists at its audited commit; it is not a combined-head test pass.
- `apps/android/app/src/androidTest/java/com/kungsbackacarcommunity/app/chatchannels/PopulatedChatHubPreview.kt`, `friends/FriendsCataloguePreview.kt`, `moderation/MessageActionsCataloguePreview.kt` (the last two share the same `.../app/` test root): local chat/friend/action states.
- `apps/android/app/src/androidTest/java/com/kungsbackacarcommunity/app/redesign/Pass3SettingsFixtures.kt`: partner/free-paid/privacy/settings/channel/block/deletion/release/membership variants. Exact keys and static-only lane evidence are in `apps/android/app/src/androidTest/fixtures/settings-pass3/README.md` in the settings snapshot.
- Existing `Wave2SocialPreview.kt` and `Wave2MapPreview.kt` under that `redesign/` test package cover selected Crown/map/live inputs only. They do not cover every claim, convoy, GPS or feature-flag state.

### Concrete differences and unresolved acceptance

- **S01–S16 / shared host:** map viewport/marker clearance, pinned close/footer and camera restoration need final-head captures and interaction evidence in both navigation variants. Source-only docking is not proof of bottom-bar visibility/tappability. S07/S08 retain device-local saved places and the real naming dialog, not reference account sync/full editor. S12's popup lacks the illustrated image input. S13/S14 retain existing car/session control models rather than inventing photo/timer inputs.
- **S17–S28:** S22 remains an embedded season board rather than the dedicated photographic page. S27 has a real countdown/expiry but no Remove callback; do not fabricate the reference action. Rewards and catalog prices must come from actual server/catalog results.
- **S29–S40 / Driver:** the compact reading hub differs from S29's landing tiles; S33's illustrated search layout is not complete. S37/S38 implement original feed/post/comments reading; create/like/save/comment/reply/report writes and any unsupported Driver onboarding destination remain outside this delivery. Driver reading itself is not deferred. S40 uses single-friend send without the illustrated map/selection page.
- **S41–S52:** event models have no photographic cover/view-count field. S45 check-in is embedded in detail; S46 lacks illustrated car/seat/arrival fields; S47 is single-recipient, not bulk select. S48 currently contains destination cards, not the illustrated upcoming-event dashboard. Convoy list/detail/recap remain native data-driven views, not the photographic S49/S51/S52 compositions. Never claim automatic save success or invent a drive link from a sample.
- **S53–S60:** no per-drive reward, driving-category distribution or 7/30/90-day chart data is available to reproduce those S53–S55 sample facts. S56 retains the real failure dialog. S60 retains the real crop/rotation/compression path without dedicated rendered acceptance. `docs/product-decisions.md` excludes top speed while `docs/gamification-system.md` records a neutral per-drive display amendment; this audit resolves neither policy document. The new lifetime maximum-speed UI fact was removed; pre-existing source facts were not expanded.
- **S57 is explicitly pending:** the G snapshot proposes 16:9 instead of the previously compact 2.5:1 card. Main must compare identical populated two-car-plus-Add states at 320 × 640, recording physical size, logical width, density and font scale. Do not approve 16:9 on source alone; retain the compact ratio if it regresses density. The subsequently requested responsive-ratio implementation is a separate source change and requires the same evidence.
- **S61–S64:** at the pinned G snapshot, profile cover inputs/rendering are absent and real-cover reuse is a separate authorized follow-up. Owner reference shortcuts exceed the existing ProfileScreen callback surface. S63 public identity/relationships/garage must retain authorized fields only. S64 is an inline expandable profile wall, not a standalone category-tab page; canonical badge IDs/tiers/counters supersede sample achievements.
- **S65–S80:** companies have no sample hero-photo field; do not synthesize partners, offers or discount codes. S70 All/Unread and S74 Closed tabs are not newly implemented. S73 uses actual backend text bounds. S77 branding/footer, S78 native consent steps without an avatar uploader, and S79's four-step welcome differ from the drawings. S80 has no formatted-price input at this layer: actual Play checkout must show store-localized prices; sample planning prices cannot fill the gap.
- **S81/S82:** catalogue page 24 explicitly identifies S81 as iOS Apple sign-in, owned on Mac mini, and S82 as the deferred cosmetic-frame picker. S81 is not Driver. Existing Supporter decoration is not evidence of a frame store, ownership, equip or purchase lifecycle.
- **S83/S84:** existing account/permission guards are retained; standalone reference compositions are not implemented or accepted. Verify restricted-account access to permitted settings/support/account exits, live-data withdrawal, denial/permanent denial/services-off and settings recovery. Granting location must never start a session.

### Pending execution, separate from historical checks

Main must first integrate and pin one immutable app revision, reconcile shared
host/capture-dispatch/localization dependencies, and record actual test outputs.
Then run the required serial Gradle/unit/Compose/instrumentation/navigation/lint/R8
checks. This audit ran none of them. Per-screen new captures must identify the
source revision, fixture or authorized account state, screenshot path/hash,
physical and logical viewport, density, locale, theme and text scale.

Compare source and rendering in the same state at the same viewport; include
360/390/430 logical widths, the narrow 320 × 640 regression, Swedish/English,
light/dark, large text, keyboard/insets, TalkBack and reduced motion. Real photos,
Mapbox tiles/GPS, auth/block/account switches, Crown claims/budgets, Play lifecycle
and affordable-device performance need separate configured evidence. Main owns
`design-qa.md`; this audit neither edits it nor declares it passed.

No version/release bump, deployment, production configuration, Driver write or
cosmetic purchase is authorized by these documentation changes.

## Historical wave-2 delivery — 2026-10-25


| Step                               | Implemented Android changes                                                                                                                                                                                                                | PR / source                                                                                                                                      |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1. Map / live / convoy             | Opaque, scrollable detail sheet with pinned close header; tools, places/search, incidents, police/sponsored details, live and convoy controls. Convoy exit actions precede the roster.                                                     | [#1178](https://github.com/SebMcCayen/carcommunity/pull/1178), `9d66d164`                                                                        |
| 2. Chat / Driver                   | Source selector, conversations, bubbles/composer, friends/DM/event-chat. Account-scoped Driver owner preserves feed/detail/scroll and reauthorizes on return; restored reads bounded to two pages.                                         | [#1180](https://github.com/SebMcCayen/carcommunity/pull/1180), `8e109413`                                                                        |
| 3. Social / events / Crown Hunt    | Social dashboard wired to existing routes, event list/detail/editor/share, Crown summaries/rewards/perks, leaderboard/KP ledger. Server-authoritative rewards and free/paid actions retained.                                              | [#1179](https://github.com/SebMcCayen/carcommunity/pull/1179), `41654fd9`                                                                        |
| 4. Garage / profile / drives       | Photo-led garage/detail/editor/picker, profile/awards hierarchy, responsive award grid, drives/detail/statistics/save-retry. Native fallback for absent photos. Compact car-named overflow menus preserve callbacks/deletion confirmation. | [#1176](https://github.com/SebMcCayen/carcommunity/pull/1176), merged; [#1182](https://github.com/SebMcCayen/carcommunity/pull/1182), `5d019ca1` |
| 5. Settings / membership / account | Grouped settings, partners/offers/application/privacy, inbox/preferences/blocks, feedback/tickets/deletion/What's new, auth/welcome/onboarding. Community/Plus/Supporter headings, existing restore flow, no hard-coded checkout price.    | [#1177](https://github.com/SebMcCayen/carcommunity/pull/1177), `fa13a39b`                                                                        |
| Integration                        | Material shape roles, semantic page headings, Social hub, scrollable Create chooser, test-only native preview and draw-fenced capture.                                                                                                     | Merged [#1181](https://github.com/SebMcCayen/carcommunity/pull/1181)                                                                             |

Historical combined app source: `7c2e64deecdbabd44b34dd964c2591ecced6e9d6`.
Integration PR #1181 and its five included component PRs merged on 2026-10-25
with the owner's explicit approval. Merge `8e044b41278e84aa8f8775e5a265eb4e9f5973c3`
preserves the exact tested Android tree and the newer upstream iOS fix. All
pre-merge checks passed, including iOS validation of shared generated strings.
Documentation SHAs are not tested app revisions. Original feature commits are
combined through merge commits. Upstream iOS work already on main is ancestry,
not iOS implementation by this lane. Four canonical plan strings also regenerate
the iOS String Catalog; no iOS behavior is changed here.

## Historical automated and visual evidence — 2026-10-25

The following results are preserved from the existing repository evidence log.
They were not rerun or independently refreshed by the 2026-10-26 source audit
and are not evidence for the new sibling commits or main's uncommitted work.

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

### Historical wave-2 gallery reproduction instructions

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

## Continuing wave-2 acceptance backlog — not invalidated by historical green CI

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
