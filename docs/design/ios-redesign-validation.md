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
the capture test asserts the map host's actual colorScheme before attaching
images, and checks the Chat/Chatt navigation title after selecting Chat.
Release/configured apps keep system behavior. The original AppleInterfaceStyle
launch default proved ineffective in rendered images and was replaced. Inspect
recaptures to confirm traits and layout. The test also checks
keyboard dismissal, tab selection, map-host UUID continuity over two Chat round
trips and My drives/Settings navigation. The UUID is exposed only by the map's
config-less placeholder (with its actual color scheme) and contains no
account/location data.

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

Recapture run <https://github.com/SebMcCayen/carcommunity/actions/runs/40132485529>
tested `ccf2b4e8`: all three complete screenshot scenarios passed, including
actual colorScheme/title assertions, Driver switching, map-host continuity,
My drives and Settings. Overall UI result: 7/8 passed. The sole remaining
failure was the pre-existing large-text legal-links test after its one swipe
failed to instantiate the lazy List row. Its follow-up uses at most six swipes
and retains both existence/hittability assertions. Release remained skipped
after that failure; final all-green validation is still required.
Artifacts: `ios-configless-screenshots` 12575099867, `ios-test-results`
12575514506. Main owns their visual comparison and does not infer fidelity from
the screenshot filenames alone.

## Honest 84-screen pass

P1 accessibility-size follow-up: main's final-head visual review of
`428835A2-B935-4E16-B0C0-5EE575BFBEE7.png` from run `40134626295` found oversized
primary/Chat chrome and a truncated Community unavailable message despite green
tests. The native fix bounds **only navigation labels** to XXXL (not accessibility
XXXL), uses stable-size decorative icons and one-line primary labels, and retains
full accessible button names/targets. Source labels retain multiline fitting;
the channel row remains horizontally scrollable, including Notifications.
The Community/hub unavailable reading state uses uncapped `.body`, natural text
height and vertical scrolling. No global Dynamic Type cap or function removal.
The new accessibility XXXL UI regression checks actual button/strip geometry,
all primary/source/channel reachability, full rendered recovery-text height
against independent UIKit body metrics, and its visible end after scrolling.
Fresh readable-recovery/Notifications attachments supplement the existing three
capture variants. Follow-up CI/rendered validation is pending; the prior green
run alone does not establish resolution of this visual finding. Android and
central evidence remain owned by main; no release is implied.

Merged-head run `40137029824` compiled and passed 1201 XCTest unit tests and
17 Swift Testing tests, but passed only 7/9 UI cases. The new geometry assertion
caught a real 33-point primary-button accessibility/hit width, and the large-text
capture failed to select Chat after tapping. The fresh Map PNG shows compact
bottom labels but also exposed scaled search/menu icons outside their circles.
The focused follow-up gives each complete 56-point-high primary slot a rectangular
content shape and fixes the two map navigation icon fonts. Assertions are retained;
Release was skipped after the UI failure and final recapture is still required.

S01 follow-up: the iOS tool rail was vertically centered. It now starts below
the 48-point avatar/top controls. Convoy status and its dynamic error row share
the same vertical stack above the rail, so they cannot be overlaid by it.
`ViewThatFits` keeps the normal rail compact and falls back to vertical scrolling
when space is short. Existing buttons, order and feature flags are unchanged.
The three capture variants assert the config-less Layers button starts within
32 points below the avatar; configured convoy/short-screen rendering remains a
separate manual check. Final-head CI and recaptures are required for this change.

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


## Android pass-three follow-up: iOS map layers

The first incremental parity slice follows catalogue S03 and the merged Android
`MapLayersPopup`: supported Traffic / Traffic alerts / 3D rows use semantic
surfaces, icons and shared supporting copy. Appearance has explicit Day, Night
and System choices; System clears the device-local manual override and applies
the current app appearance to the existing renderer. The focus slider is a
collapsed disclosure, preserving its existing range, persistence and callbacks.
Large accessibility text stacks the appearance choices and scrolls content while
keeping the native close action outside the scroller.

Config-less DEBUG UI tests set their SwiftUI text size before the first frame
using `-KCCUITestTextSize`; configured/Release builds ignore this fixture. This
avoids UIKit launch-default state leaking between successive test launches.
All large-text geometry/readability assertions remain enabled. These controlled
SwiftUI fixtures do not replace real-device testing of OS-wide text-size changes.

Scope is deliberately limited: this does not claim Android's complete pass-three
screen design, docked footer geometry, incident-age filters or Crown participation
controls have been ported. Existing iOS feature/privacy gates, incident attribution,
map instance/session lifetime and external navigation remain unchanged.
Unit regressions cover explicit and System transitions. Config-less UI cases
cover English selection/reopening/focus and Swedish dark accessibility text.
These new native cases require passing CI; authored tests are not executed proof.
No Mapbox rendering, production Firebase data, Apple service or purchase is needed
for these UI checks. Physical-device validation remains pending enrollment.


## Android pass-three follow-up: feature reading screens (2026-10-26)

Compared the merged Android chat/event/garage/profile presentation and the checked-in
`android-wave2-2026-10-25` previews. Native iOS reading cards now share semantic
surfaces and borders rather than copying image assets, portraits or sample data.

- Chat keeps every existing channel/source and coordinator. The source selector
  shares one outlined capsule; reply surfaces and message typography use semantic
  roles. The composer retains its full draft/send policy with a bounded native
  multiline editor viewport. At accessibility sizes Send uses a labelled icon;
  extra lines scroll inside the field so the keyboard does not push Send away.
- Events use public-teaser calendar/place cards in the list and detail. No private
  address/description is moved outside its existing gate. RSVP remains server-driven;
  choices wrap/stack, tallies remain neutral, and recovery copy can scroll.
- Garage keeps its owner detail/edit/photo/delete/main-car actions and allowance.
  Cover cards use the resolved real image, a 16:9 crop, a native missing-image
  fallback and cyan main-car emphasis. Nothing is hidden after a downgrade.
- Profile groups the real avatar/name/bio, retains the existing edit/upload state,
  and puts Back outside the scroller. The existing points ledger entry is kept.

`FeatureDesignLayoutTests` measures the actual SwiftUI garage/event/chat/points
widgets at 320/390 points, Swedish/English, dark/light and normal/accessibility
text using synthetic data without image URLs. These are wrapping/width checks,
not screenshot-similarity tests or a full profile-edit/purchase/authentication test.
The config-less UI navigation case captures Chat, unavailable Garage and Events,
then returns through the native Back/tab routes. Config-less mode intentionally
has no owner-profile shortcut; no authentication bypass was added for testing.
PR CI is the source of executed native test results. Final physical-device and
real-data visual acceptance still remains separate from these checks.

## Map reading dock and session chrome follow-up (2026-10-27)

Road/police incident details, the saved-place picker and nearby-driver identity
now use one native reading panel inside the shell body, above primary navigation.
Its Close action and accessibility escape stay outside the content scroller.
Selection is dismissed on tab/route/search changes and before other map modals;
incident disappearance follows the authoritative coordinator. Report/remove/
verify/ownership/imported-source/busy rules and saved-place storage stay in their
existing coordinators. Feedback remains on the shell's existing alert surface.
This is not a new long-press place-actions implementation or a replacement for
external navigation. Report/editor, Crown, sponsored and convoy member/invite
modals remain separate deliveries/surfaces.

Convoy status moves from the top tool stack into a measured bottom dock. Active
live sharing supplies management, the existing single-session Stop confirmation
when applicable, and Hide me now; this active escape hatch is not gated by the
start-sharing flag. Convoy exit still uses its role-specific confirmation.
The rail and reaction controls reserve the measured dock height. The same retained
map host and projected marker layers receive the bottom clearance, keeping their
coordinate spaces together; selecting an authorized marker reuses centerOn.
Configured Mapbox camera/projection and short-screen behavior still need a real
rendered check. No new map instance, GPS provider, listener or backend command is
introduced. A native scroll rail replaces alternative duplicated rail layouts.

Nearby detail selection stores a uid, never a retained marker snapshot. It
re-resolves the current permitted/fresh marker and image URL on every update/tick;
an absent marker clears the panel. Markers use the shared driver accent and
respect Reduce Motion. No new avatar lookup, profile permission or read is added.
The full live-management route also uses semantic reading colors, Dynamic Type
and pinned Back; its permission/start/stop/hide logic is unchanged. Only compact
map-management chrome limits its navigation label size; reading content and
error text retain accessibility text sizes.

Native layout tests exercise actual session chrome at narrow widths and normal/
accessibility text plus a long permitted driver name without network images.
The config-less UI case uses the real empty saved-place dock, measures Close and
footer separation, scrolls content and taps the primary Chat tab while the dock
is open. These require native CI, and do not establish real GPS/Mapbox/session
or VoiceOver acceptance. No live Firebase or Mapbox runtime call, Apple service,
purchase, paid review or production deploy is needed for these tests.

## Crown Hunt design follow-up (2026-10-27)

The native hub now has a pinned horizontal Overview / Statistics / gated Shop
strip inside one NavigationStack. Full tab labels scroll at accessibility text
sizes, rather than shrink or truncate. Overview uses actual season aggregates;
Statistics retains lifetime counters and the season board. Earned points are
never relabelled as spendable balance. The shop alone uses its existing ledger
balance. Owner claim history and canonical instructions remain native push
routes. Three previously missing claim-history strings are now in the shared
SV/EN contract and generated into both native platforms; no Android behavior or
release configuration changes.

Stats, standings, history, shop and inventory use the shared semantic reading
cards and page surfaces. Long names, costs and result labels wrap/stack at large
text sizes. Native Back and map-panel Close stay outside reading scrollers.
Shop/activation availability, affordability, in-flight guards and command payloads
remain authoritative in the original coordinators. No purchase or deployment is
performed by the design tests, and no new automatic backend read is introduced.

Map crowns use one crisp system crown glyph on a small opaque navy tile, one
accent border and 1–4 rank bars, using generated common/uncommon/rare/gold tokens.
Spawn rank comes from its real rarity; curated points use visual bands of their
actual reward without acquiring a new rarity. Metadata crosses the existing
renderer seam as primitives. Proximity uses the existing fresh-fix tracker and
updates once per second; it is not a promise of collection eligibility. No glow,
perpetual pulse or raster image generation is added. Reduced Motion disables the
short opacity transition. Shared spawns already collected by this reader are
hidden locally; an older in-flight refresh cannot erase a just-confirmed award; shared server data and the original collector read are retained.
Curated-point cooldown/suppression policy is unchanged; full Android UTC-day
suppression and 320 ms shrink animation parity are not claimed by this patch.

Collection uses the shared map reading dock above the footer. An exclusive
spawn's existing removal still occurs in its coordinator, while an awarded
result can keep the reading panel open until dismissed. The result shows actual
server pointsAwarded (including a clipped amount), never the advertised reward
as an invented award. Refusal/retry, source-specific results and safety copy are
retained; terminal results disable the presentation's Collect action. Switching
routes/tabs, selecting other map content or revoking availability closes it.
No claim radius, dwell/speed requirement, budget, flag default or server API is
changed.

New native tests exercise real SwiftUI reading widgets at narrow SV/EN light/dark
and accessibility sizes, the config-less hub's tabs/history/instructions/back
navigation, fresh/stale/expired marker metadata and a fake-repository exclusive
award with a clipped reward. These are not real GPS/Mapbox/purchase acceptance
or a physical-iPhone visual comparison. Native CI must pass before merge; the PR
records the exact executed run. Production Firebase deploy and signed Android
release workflows are outside these paths/manual-only respectively. iOS artifact
uploads stay opt-in; no paid review, production write or Mapbox runtime is needed.

## Private social reading surfaces (2026-10-27)

The iOS DM inbox, private thread/composer, friends, notification inbox and native
Driver feed/detail now use the generated semantic page/surface/accent roles.
Inbox cards distinguish unread items with cyan borders and count/status text;
notification timestamps have their own line. Already-read notifications render
as readable static content, while unread rows retain the existing single-flight
mark-read command and server-authoritative styling. No notification destination
or destructive action is added by this presentation change.

Friend identities, pending requests and actual point balances stay in native
list sections, retaining sort, nickname ambiguity selection, independent request
buttons and confirmation-gated swipe removal. Reading text uses native Dynamic
Type; action groups stack at accessibility sizes. No avatar storage reads or
per-author queries are added. Unavailable and empty inbox states stay distinct
and use scrollable reading cards rather than fixed-height centered copy.

The DM composer bounds only the input viewport, retaining the full bound draft,
UTF-16 send guard, optimistic message ownership, retry client ID and neutral
terminal errors. Bubbles retain delivery state, optional real timestamps and
server-built reply previews. Driver posts remain original read-only posts and
comments with inline source attribution and actual counts, not chat messages.
Its UID identity, active/scene invalidation lifecycle, access checks and bounded
pagination are unchanged. Supported image URLs keep their existing validation;
a failed image now resolves to a neutral placeholder rather than an endless
loading state. No like/create/report controls or automatic onboarding are added.

SocialDesignTests measure the actual production reading widgets at 288/358 pt,
SV/EN, light/dark and largest accessibility text, including unread/read cards,
long identities, friend actions/points, private delivery/reply states, Driver
counts and the bounded long-draft composer. The full existing native suite also
covers block filtering, send/retry, authoritative mark-read, Driver access and
account switching. Layout tests and the config-less UI suite are not physical
phone visual inspection or production social acceptance. Testing real friend
requests, notification delivery and Driver roles remains a separate acceptance
step with isolated accounts. No production backend deploy, signed distribution,
Mapbox runtime, paid service or artifact-upload opt-in is needed for this PR.

## Settings, notification preferences, privacy and block management (2026-10-27)

Settings destinations retain their existing callback availability and canonical
HTTPS legal-link policy. Navigation rows use semantic surface/icon roles,
uncapped body text, 48 pt labels and distinct external/destructive affordances.
The account-only destinations stay absent in config-less builds; this change
adds no authentication bypass or new settings destination. Android's separate
automatic-theme scheduling feature is outside this iOS presentation pass.

Notification preferences use semantic permission/category cards and native
labelled switches. Essential account notices remain enabled and disabled for
editing; their view bindings also reject edits before invoking the existing
coordinator. Saving/failure feedback stays in a measured bottom safe-area inset,
above the shell navigation, so an edit near the bottom of the category scroller
cannot hide the aggregate result. Category controls disable during the existing
single-flight write, so extra taps are visibly unavailable instead of dropped. Error dismissal uses the existing reset guard
without a new write or optimistic preference change. Every existing category/channel, save-error state, permission
request guard and OS-settings destination is retained. Permission is refreshed
on entry as before; no authorization prompt is requested automatically by
rendering a card or running its layout tests.

Privacy explanations, switches and independent Save/status controls now share
two reading cards. Both bind to the existing pending draft, require an
authoritative loaded snapshot, and retain independent single-flight commands.
Leaderboard opt-out inversion, defaults, dirty drafts and subscription teardown
remain in the unchanged coordinator/repository. Rendering is not consent and
never automatically saves a preference.

Blocked users retain the caller-owned outgoing list, actual optional dates,
neutral missing-name fallback and confirmation-gated unblock request. The
structured task, global working guard and errors stay with the original screen
and coordinator. Only Back stays outside the reading scroller so long names,
empty/unavailable/retry cards and action errors cannot displace it. No symmetric
hidden-set data, avatar lookup or private user lookup is introduced.

Shared reading-card border strokes explicitly ignore hit testing; interactive
content keeps its native targets. SettingsDesignTests measure the actual row,
permission/category, privacy/status, blocked-user and recovery widgets at
288/358 pt, SV/EN, light/dark and normal/largest accessibility text. A binding
regression test verifies essential switches reject writes while the independent
social channels still forward the selected edit. Existing config-less Settings
navigation tests retain real News/Back taps and now check 48 pt legal targets;
existing coordinator and legal URL tests remain part of the full native suite.
These checks do not establish real-account privacy writes, OS permission-return
acceptance, production unblock behavior or physical-iPhone visual comparison.
No backend, rules, workflow, token, localization or paid-service changes are
required; native PR CI must pass before merge and records the executed result.

## Partner directory, offers and application (2026-10-27)

Partners now uses the shared semantic page/card roles for the directory, saved
list, company information, public offer previews, member detail and code box.
Directory/Saved navigation remains a local presentation choice with the same
company/offer destination IDs. Its pinned horizontal tabs keep full Dynamic Type
labels and 48 pt targets rather than shrinking a segmented control. Native Back
and the existing navigation stack remain outside the reading scroller.

The existing coordinator still decides paid/admin access, active-offer visibility,
subscription revocation, expansion, private details, code reveal and saved state.
Public previews never receive private instructions, terms or codes. Code reveal
is still an explicit button command; shown codes retain their offer-ID guard and
manual text selection. No automatic reveal, redemption, clipboard write, new
listener or analytics/consent behavior is introduced. Existing validated HTTPS,
phone and Apple Maps destinations are retained with larger native Link labels.
Partner imagery is a local system fallback, without new logo/avatar fetches.

Company/offer paging and all existing limited-dataset warnings stay intact.
Directory offer counts remain absent until the existing exhaustive-data guard
allows an actual count; nil is not replaced by zero. Saved-offer navigation still
resolves its original company and focused offer. Error/retry, loading, membership,
empty and unavailable states now use readable surfaces rather than introducing
new destinations or unauthenticated test access.

The application keeps the same form state, category choices, keyboard/content
types, UTF-16 validation, normalization, optional-field omission, command payload,
single-flight submission and once-per-presentation preparation guard. Fields use
persistent visual labels and their native accessible labels; the message bounds
only its input viewport and keeps the full bound draft. Pending fields lock while
the immutable submitted input is in flight. Validation/duplicate/generic errors
and the success Close callback retain their original meaning; success copy can
scroll at large text sizes. No application is submitted by the layout tests.

PartnerDesignTests measure actual public/saved/locked/member-control widgets,
all offer-type labels, synthetic revealed-code wrapping, tabs, field viewport and
submission labels at narrow SV/EN light/dark and largest accessibility text. The
member-control layout fixture has no repository, signed-in UID or running session;
it is not a runtime authorization shortcut. Existing coordinator/model/application
tests cover access revocation, stale code results, pagination, external URL policy,
validation and duplicate submission. The config-less shell still hides Partners
and application routes without their configured services. These checks are not
live paid-account/redemption/consent acceptance, real application submission or
physical-iPhone visual comparison. No backend, rules, token, workflow, localization,
production deploy or paid-service/artifact-upload opt-in changes are required.
