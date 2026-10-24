# iOS native redesign validation

Status: implementation checkpoint, **not release approval**. Linux host; no
Xcode, simulator, physical iPhone, or rendered application evidence available
locally. Do not interpret the authored XCTest scenarios as executed tests.

## Scope and provenance

Worktree `/tmp/carcommunity-redesign-ios`, branch `codex/redesign-ios`, initially
based on shared foundation `41e60f05` over `1b63f152`. The branch incorporates
`origin/main` at `fdf180f1`: PR #1165 map visibility, #1166 location puck, and
#1167 StoreKit updates. Merge `d8b64d82` preserves those fixes and the new shell.
Foundation references/formatting `b8dd6127` and canonical Driver localization
`91d6482d` are also incorporated. No work was done on those PR branches or the
independent #1168 preview workflow.

The #1165 fix removes the opaque TabView content host. This implementation keeps
its direct-content/safeAreaInset architecture, with a native SwiftUI primary bar.
One MapHomeView and the map feature-task host stay mounted across tab changes.
This additionally avoids destroying convoy/map subscriptions when leaving Map.
Opaque Chat pauses map work through the existing MapCover/ShellMapHost policy;
the separate explicitly started live and drive providers remain independent.

References inspected: the full brief/catalogue text, extracted sheets 000, 007,
013, 014, 015, and the checked-in reference set under
`references/2026-10-24/`. Reference photographs, people, positions, rewards and
statistics are not runtime fixtures.

## Implemented slice

- Semantic graphite reading surfaces, opaque map controls, cyan driver/chat
  accents and dark foreground on primary gold actions. KccTheme resolves the
  generated light/dark roles; generated tokens and localization are not edited
  by this implementation. System theme remains the default.
- Convoy driver rings, direction arrows and fallback images use the driver
  semantic roles. Marker-list changes do not animate removed/stale drivers;
  the existing position/privacy filtering remains authoritative.
- Primary navigation: Map / Chat / existing Create-or-Stop / Social / Garage.
  Create retains its existing vehicle/single/convoy, permission, pending command
  and stop flow. Garage remains the actual vehicle list, without an extra hub.
- Search begins as a labelled 48-point circle in the upper left. Expansion
  focuses the real address field; selection, explicit close and accessibility
  escape collapse it. The avatar/menu position stays fixed. Config-less search
  uses an empty transient store and unavailable search client; no invented UID,
  production request or fake result is needed to test the keyboard/recovery UI.
- The right rail retains report, layers, compass, saved places, feature-gated
  Perks and the existing live control. The duplicate map Chat circle is removed.
  Existing contextual convoy/reaction controls and flags remain wired. iOS
  navigation remains external under ADR-002; Android's turn-by-turn rail belongs
  to the separate Android delivery.
- Chat is opaque, with existing Community, Convoys, Friends/DM and Notifications
  content and an Events entry into existing event routes. DM unread counts use
  the existing block-filtered inbox; Driver activity is not included. Channel
  drafts live in their account/channel coordinator. Retained covered/background
  channels do not stamp new messages read until visible again.
- My drives is available from the profile menu and Profile, using the existing
  DrivesPanel/history coordinator, details, stats, delete/share and route sheets.
  A successful auto-kept drive exposes a direct My drives map action. The
  existing non-dismissible failed-save/retry/discard sheet is retained.
- Legacy `.history` remains a separate identity outside primary allCases.
  Initial History restoration opens Map -> Profile -> My drives; an additional
  supplied child route stacks above it. Chat never reuses History's identity.
  iOS had no persisted navigation decoder at this baseline: the migration seam
  is ShellView's existing initialTab/initialRoute API. No drive data is moved or
  deleted; selected-drive/detail state remains inside the original DrivesPanel.

## Driver integration boundary

ChatHubScreen accepts native `driverContent: AnyView?`; ShellView mounts the real
DriverFeedScreen behind its Chats/Driver selector. The sibling's iOS-only commit
`d00b2ab737132ff06cc7f6d90d90fc3a65709cd9` was cherry-picked as `b58ad6e4`, adding
`KCC/DriverFeed/**` and `KCCTests/DriverFeed/**`. No uncommitted files were copied.
The mount is:

```swift
DriverFeedScreen(
    uid: signedInUid,
    strings: DriverFeedStrings(lookup: { key in
        Bundle.main.localizedString(forKey: key, value: nil, table: nil)
    }),
    isActive: readingActive
)
```

The host passes only the authenticated UID and localization lookup; it makes no
direct Driver queries, creates no second profile, and exposes no paid cosmetics
or unsupported write actions. Canonical driver.feed.* strings are provided by
the shared foundation, including canonicalOnboarding from `d62db557`. The
adapter calls the new `driverFeed-list` and `driverFeed-detail` bridge once per
page; the backend implementation/deployment is a separate dependency. It does
not fall back to legacy content queries. Driver onboarding, bilateral blocking,
pagination, stale account responses and required backend deployment still need
their own executed evidence. No deployment is authorized by this branch.

Lifecycle follow-up `8071b6d2` is included. Source/route/tab/scene visibility
flows through ShellDriverFeed into `isActive`; inactive readers cancel requests
and listeners, clear content and preserve the selected detail ID for fresh
authorization on return. Chat and Driver views stay mounted to retain source
selection and native view identity; read receipts are limited to visible chat.

`project.yml` recursively includes `KCC` and `KCCTests`, so both new DriverFeed
folders belong to the existing app/test targets. Regenerate the committed
project after integrating those files. CI performs this regeneration before
building and uploads `ios-generated-project`. The committed project snapshot
was synchronized byte-for-byte with that macOS-generated pbxproj from run
`40130932474` (artifact `12575115603`): five Driver sources and the Driver test
source are included in their proper app/test targets. The scheme had no drift.
That superseded run was cancelled after the lifecycle update was pushed; it
does not establish passing compilation/tests.

```sh
cd apps/ios
xcodegen generate
```

## Actual local results

| Check | Actual result |
| --- | --- |
| iOS release source preflight | PASS, source metadata only; signing/configuration excluded |
| Python release-preflight regression tests | PASS, 7 tests |
| iOS token and string generators | PASS, no generated-file drift |
| Android token generator | PASS, no generated-file drift |
| git diff --check | PASS at checkpoint |
| Swift compiler / simulator unit tests | PASS on CI head 6d6705db: 1201 XCTest + 17 Swift Testing tests |
| Simulator UI tests | 5/8 PASS on 6d6705db; three capture cases failed finding the Driver source button |
| Unsigned device Release build | SKIPPED after UI failure; final-head CI still required |
| Rendered screenshots / reference comparison | Captures exist for 6d6705db; invalid dark fixture and title/nav roles found by main; recapture required |
| Configured-device functionality | NOT RUN |

Commands run from the repository root:

```sh
python3 apps/ios/scripts/release-preflight.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s apps/ios/scripts -p 'test_*.py'
node apps/ios/scripts/generate-tokens.mjs
node apps/ios/scripts/generate-strings.mjs
node apps/android/scripts/generate-tokens.mjs
git diff --exit-code -- apps/ios/KCC/Design/Tokens.swift apps/ios/KCC/Resources/Localizable.xcstrings apps/android/app/src/main/java/com/kungsbackacarcommunity/app/design/Tokens.kt
git diff --check
```

## macOS functional and rendered evidence gate

The existing Validate iOS workflow runs simulator unit/UI tests and an unsigned
generic-device Release build. It now uploads `ios-test-results` (.xcresult) on
success and failure. `xcresulttool export attachments` also exports attachments
to `ios-configless-screenshots`, with its JSON manifest and screenshot PNGs.
No Firebase configuration or secrets are injected. Screenshots are taken only
after the UI test verifies the config-less Settings identity.

ShellSmokeTests adds actual XCTest screenshot attachments (`keepAlways`) for
these state/launch combinations:

| Capture suffix | State |
| --- | --- |
| S01-map-collapsed-search | Semantic placeholder map and collapsed search launcher |
| S02-search-keyboard | Actual address field and keyboard |
| S29-chat-opaque | Full-screen native Chat, unavailable backend state |
| S37-driver-restricted-configless | Actual native Driver adapter without an authenticated account |
| S61-my-drives-entry | Profile/menu entry to history |
| S53-my-drives-unavailable | Actual history view without configured backend |
| S69-settings | Native Settings |

Prefixes: `configless-en-Light-default`, `configless-sv-Dark-default`, and
`configless-en-Dark-AXXXL`. A DEBUG-only `-KCCUITestTheme` fixture explicitly
sets SwiftUI preferredColorScheme, guarded on Firebase being unconfigured;
Release/configured apps keep system behavior. The original AppleInterfaceStyle
launch default proved ineffective in rendered images and was replaced. Inspect
recaptures to confirm traits and layout. The test also checks
keyboard dismissal, tab selection, map-host UUID continuity over two Chat round
trips and My drives/Settings navigation. The UUID is exposed only by the map's
config-less placeholder and contains no account/location data.

New unit scenarios cover the explicit History identity/parent stack, opaque Chat
and My drives despite stale search state, camera retention over cover changes,
and covered-channel unread/draft isolation. Existing map-puck, StoreKit,
account/notification and session tests remain required.

Equivalent manual macOS commands (without GoogleService-Info.plist):

```sh
cd apps/ios
xcodebuild -project KCC.xcodeproj -scheme KCC -destination 'platform=iOS Simulator,name=<available iPhone>' -parallel-testing-enabled NO -resultBundlePath /tmp/ios-redesign-tests.xcresult test
xcodebuild -project KCC.xcodeproj -scheme KCC -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
xcrun xcresulttool export attachments --path /tmp/ios-redesign-tests.xcresult --output-path /tmp/ios-redesign-screenshots
```

CI run URL, exact tested commit, successful test/build result, artifact URLs and
reviewed screenshot filenames must be appended after main pushes/runs CI.
**The first captures do not establish valid dark-mode fidelity.** A config-less
placeholder proves shell composition only, never real map tiles,
search results, GPS, a live session, Driver access, or Apple purchases.

Draft PR: <https://github.com/SebMcCayen/carcommunity/pull/1170>, base
`codex/redesign-design-language`. Initial validation checkpoint: `6d6705db`, run
<https://github.com/SebMcCayen/carcommunity/actions/runs/40131037243>.
Swift compilation and unit tests passed; three UI capture cases failed at the
Driver selector. Pushed 573fa7de adds explicit Chat accessibility containment
to retain source-control IDs. Main reviewed its predecessor's images and found
that dark-labelled images were light, the child Community title replaced Chat,
and the selected primary-tab underline used cyan. The next batched fix supplies
the explicit theme fixture, removes the child navigation-title preference and
uses gold for primary navigation. Main retrieves/reviews recaptures.

## Honest 84-screen pass

No catalogue family is marked visually complete. The table distinguishes code
adoption from future rendered/configured-device comparison.

| Catalogue IDs | Implemented/preserved here | Remaining pass |
| --- | --- | --- |
| S01-S04 | Shell, collapsed/focused search, rail and semantic controls | Actual map/search/layers/place menu in both themes and widths |
| S05-S08 | Existing external navigation/saved places | iOS external intent, empty/error/save, keyboard; S05/S06 Android-specific |
| S09-S12 | Existing reports/incidents/sponsored routes retained | Report lifecycle, warning/detail focus, sponsored labels |
| S13-S16 | Existing Create/Stop/live/convoy providers retained | Real start/stop/hide/expiry, fresh/stale GPS, focus/driver preview |
| S17-S20 | Claims and crown feature gates retained | Claim states, duplicate/budget/error, tier rendering, motion |
| S21-S24 | Existing stats/season/rules/leaderboard retained | Detailed screen and accessibility comparison |
| S25-S28 | Perks map control surface adopted; economy retained | Inventory/deploy/ledger; open-sheet flag revocation |
| S29-S32 | Opaque Chat + existing community/convoy routes | Rendered keyboard/composer, unread, reply flags, block/retry |
| S33-S36 | DM/inbox/friends routes and guards retained | Real unread/DM/new-dialogue, blocked/restricted identities |
| S37-S40 | Actual native Driver adapter mounted; existing share routes retained | Bridge deployment, bounded pages/details/access and real renders |
| S41-S44 | Existing event/list/detail/editor/chat routes retained | RSVP/editor/owner actions, chat flag and notification links |
| S45-S48 | Social shell updated, other event paths retained | Check-in access, group drive, sharing, long copy |
| S49-S52 | Existing convoy management/session behavior retained | Leader/member/invite/leave/end lifecycle and render pass |
| S53-S56 | My drives entries/restoration/direct post-drive path; existing data flows | Saved detail/stats/replay/delete, upload retry and tier access |
| S57-S60 | Direct Garage tab; semantic cards/fallbacks | Real images/form/crop/main-car/limits and delete |
| S61-S64 | Profile My drives entry; semantic avatar/points surface | Actual profile/edit/member/badges, long localized copy |
| S65-S68 | Partner/application/privacy routes retained | Free/paid content, consent and statistics access |
| S69-S72 | Settings screenshot targets; existing inbox/preferences/blocking | Full list, OS permission return, all preference categories |
| S73-S76 | Help/deletion/What's new flows retained | Reauthentication, failure/recovery, content and scroll variants |
| S77-S80 | Existing onboarding/membership retained | Real sign-in and StoreKit pending/cancel/restore/revoke; S77 Android-specific |
| S81-S84 | iOS identity/restricted/privacy paths retained | Real Apple sign-in/restricted/permission checks; S82 paid cosmetics deferred |

Also pending: 360/390/430 logical widths, Swedish/English long content,
VoiceOver focus order/escape, reduced motion, background/foreground and account
replacement, physical iPhone map performance and affordable-Android parity.
Stockholm midnight/DST, real GPS freshness, App Check, purchases and production
backend correctness cannot be inferred from screenshots or a green CI build.

Android implementation and validation belong to the separate Android branch/PR.
This branch does not claim cross-platform completion. The user subsequently
authorized pushing this lane and opening a draft PR against the shared design
foundation to start macOS CI. Main owns screenshot retrieval/review. No PR
merge, release, production data access or backend deployment is performed.
