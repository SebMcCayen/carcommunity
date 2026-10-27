# CarCommunity map-first design language

Status: implementation in progress; not a release approval. Adopted 2026-10-24.

## Sources and precedence

This specification transcribes the user-supplied `carcommunity-redesign-underlag-v3.pdf`
(17 pages) and `carcommunity-bildkatalog-84-vyer.pdf` (25 pages, S01-S84).
The selected direction is the dark, driver-focused map on brief page 17 and
catalogue S01. The PDFs were based on repository snapshot
`1916faa292ea12d65a572cc8033d52cef5b1a20e`; implementation starts from current
main and must preserve subsequent fixes. The images are concepts with sample
people, vehicles, rewards, and geography, not production data or runtime proof.

Approved architecture and backend behavior take precedence over illustrative
image text. This document supersedes conflicting mobile presentation guidance
in `docs/design-system.md`; that document continues to cover general components.
No screenshot grants membership, changes a claim radius, invents a badge, or
changes a feature flag default. Existing billing, identity, permission, safety,
privacy, blocking, and account restrictions remain authoritative.

The user explicitly included the native Driver feed in this delivery on
2026-10-24. Cosmetic purchases remain a later delivery. Preserve native Kotlin /
Compose and Swift / SwiftUI; do not introduce a cross-platform runtime.

## Visual identity and tokens

The map is the scene; community content supplies the focus. Use graphite/navy
surfaces, cyan for active driver position, and crown gold for primary actions and
game rewards. Keep decoration restrained and preserve Mapbox attribution.

| Role                   | Dark      | Light     | Contract key        |
| ---------------------- | --------- | --------- | ------------------- |
| Page                   | `#040211` | `#F8F6EF` | `pageBackground`    |
| Card / reading surface | `#121A27` | `#FFFFFF` | `surfaceBackground` |
| Subtle surface         | `#0B111B` | `#F0EBDC` | `subtleBackground`  |
| Map overlay            | `#121A27` | `#FFFFFF` | `overlayBackground` |
| Overlay border         | `#526172` | `#B4B1AD` | `overlayBorder`     |
| Primary text           | `#F8F6EF` | `#040211` | `textPrimary`       |
| Supporting text        | `#B4B1AD` | `#6D6C6D` | `textSecondary`     |
| Primary action         | `#EAB54B` | `#EAB54B` | `brandPrimary`      |
| Text on primary action | `#040211` | `#040211` | `onBrandPrimary`    |
| Active driver          | `#49DDEB` | `#007582` | `driverActive`      |
| Text on driver accent  | `#040211` | `#FFFFFF` | `onDriverActive`    |

The supplied dark overlay and cyan are source values. The light driver accent,
subtle navy, and border are implementation companions selected for legibility;
they are not values claimed by the source PDF. Green still signals success;
red means error, destructive action, or Stop. Cyan is not a membership status.
Use dark text on gold. Never use white body text on gold, or gold body text on
ivory. Opaque reading panels must remain legible on both bright and dark tiles.

The only canonical token source is `contracts/design-tokens/tokens.json`,
validated by its JSON schema. Run both native generators after edits:

```sh
node apps/android/scripts/generate-tokens.mjs
node apps/ios/scripts/generate-tokens.mjs
```

Do not edit generated `Tokens.kt` or `Tokens.swift` manually. Admin has an existing
separate reference implementation and is outside this visual migration.

Use Roboto/system on Android and SF/system on iOS. Body 16, supporting 14, title
18, heading 24; 12 is supplementary metadata only. Respect system text scaling.
Spacing is 4/8/12/16/20/24/32/40/48, side margins 16, radii 8/12/16/24.
Icon-only controls need accessible names and at least 48 dp / 44 pt hit areas.
Use existing platform icons and licensed source assets. Mockup portraits and cars
are examples: runtime screens render the real profile/garage with fallbacks.
Keep light/dark/system selection, with System the default.

## Navigation and route continuity

The five primary choices, in order, are **Karta, Chatt, +, Social, Garage**.

| Entry                 | Destination and preserved behavior                                         |
| --------------------- | -------------------------------------------------------------------------- |
| Karta                 | Persistent map, collapsed search launcher, map tools, selected entity      |
| Chatt                 | Opaque full-screen chat hub; Chats and Driver posts remain distinct        |
| +                     | Existing car/single/convoy chooser; active session opens management / Stop |
| Social                | Events, Crown Hunt hub, leaderboards, partners, convoy access              |
| Garage                | Vehicle list directly, existing create/edit/photos/delete flows            |
| Avatar / profile menu | Profile, My drives, friends, partner application, Settings                 |
| My drives             | Existing history, statistics, detail, route replay, deletion, save/retry   |

Keep the legacy History identity for persisted-state migration. Never reuse its
ordinal or identifier for Chat. Restore old History to My drives, including its
selected drive and parent/back behavior; do not delete or migrate history data.
Post-drive links still reach history. Back closes the nearest child, then its
panel, then returns to the map. Preserve notification links and drafts.

The map instance is composed once. A readable full-screen Chat or detail uses
`MapCover.Opaque`: pause map rendering while preserving camera and the explicit
live/convoy session. Closing it resumes the same map. Transparent panels keep
the visible map active. Tab changes must not start or stop sharing.

## Shared Android page chrome and actions

Use `AeroPage` / `AeroLazyPage` and the existing native Back dispatcher. The
authenticated shell may provide `LocalAeroOwnerHeader` with the already-resolved
owner avatar and effective Supporter visibility. It is a profile shortcut, not a
new image resolver or entitlement. Keep its 48dp hit target and native fallback.
Do not show a duplicate shortcut on the owner's Profile or inside the Social hub;
Social keeps the existing profile shortcut on the map shell above its panel.
Editors, crop flows and modal pickers that own save/cancel/discard must set
`ownerShortcutEnabled = false` so a decorative header never adds an exit that
loses their draft or bypasses a
confirmation. Lazy screens retain their list position and pinned Back behavior.

Use `KccButton` and `KccOutlinedButton` for ordinary filled/outlined actions:
shared medium corner radius, at least 48dp height, existing Material disabled,
focus, ripple and semantics behavior. Explicit shapes/colours for specialist
controls remain supported. Text/icon actions retain their own native roles;
compact chat navigation must not grow into full-width primary actions. Event
detail secondary actions use equal-width columns with wrapping native labels.

## Map composition

At the 390 x 844 reference size, aim to leave two thirds of the map visible
before a detail opens. No logo in the top left. Search starts as one circular
icon. Tap expands the real search, focuses the field, and opens the keyboard;
selection, Back, and explicit dismissal collapse it. Keep the avatar stable.
Empty results and connection failures are distinct states.

The right rail uses the same order in normal and Android navigation modes:
Report incident, Layers, Compass, Saved places, Perks. Flagged-off controls leave
no holes. Wave is contextual above the rail under existing proximity rules.
Remove the Chat circle because Chat has a primary tab. Do not remove other
navigation controls or change their underlying functions.

One selected place, driver, crown, or incident owns detail focus. Use a common
sheet language with a compact preview, half-height reading, and expanded reading
where needed; content and text size determine height. The selected marker must
remain visible above the sheet. Priority is OS/permission and session controls,
navigation/incident warning, selected detail, convoy/live status, other markers,
then decoration. Stop and Hide me now are always reachable.

Android reading panels use `MapDetailSheet` inside the activity-window
`DockedMapSheetHost`. The shell registers its measured body through
`mapSheetViewport`; that body already excludes the persistent navigation bar and
the device's system navigation inset. A chat overlay registers its own viewport
at higher priority, including the existing keyboard clearance. Do not use a new
`Popup` or modal window for a bottom-docked map reading panel: a window's bottom
is not the shell body's bottom. Keep destructive confirmations as explicit
confirmations. Tab changes dismiss reading panels before navigation, and the
footer must remain both visible and physically tappable. Validate geometry and
OS-injected taps, rather than semantics-only clicks, at narrow widths and large
text sizes. Header close/back actions stay reachable while panel content scrolls.

In map browsing, active live/convoy controls dock immediately above the footer
(S16); they must not occupy the search/avatar row. Reserve their measured height
for other bottom overlays. Browsing starts at the synchronously restored focus
distance before the first GPS fix, including when using a cached location.
Changing focus distance must not recreate the map or alter navigation/convoy-fit
camera policies. S03 appearance choices are Day, Night and System; keep the
existing traffic/incident/3D/Crown gates and all supported incident-age filters.
Large text wraps or scrolls controls instead of shrinking reading text.

Driver markers use permitted avatar/vehicle data, a cyan position ring and
selection emphasis. Only show names when selected or at useful zoom. Do not
invent positions after updates stop. Driver preview shows permitted name/car,
actual sharing state, and View profile. DM/friend actions retain existing rules.
Crown markers use compact rounded-square tiles with one crown silhouette,
value-tier and claim-state cues, never an avatar or car inside. Follow
[crown-map-markers.md](crown-map-markers.md) for the 2026-10-26 refinement;
it replaces the noisy multiply outlined transparent rings. Sponsored pins retain a sponsored label and action.

## Independent modules and state

| Module                  | Input / output                                           | Boundary                                          |
| ----------------------- | -------------------------------------------------------- | ------------------------------------------------- |
| Map scene               | Visible map state + selected entity -> tap/camera intent | Single map lifecycle; no new per-marker listeners |
| Driver marker / preview | Filtered driver state -> profile action                  | Account/block/privacy rules precede presentation  |
| Crown marker / detail   | Point or spawn target + claim UI -> navigate/claim       | Separate adapters, server result codes preserved  |
| Crown hub / inventory   | Existing stats/season/catalog/balance/inventory          | KP economy and server activation remain intact    |
| Live session bar        | Session state -> manage/hide                             | No purchase or cosmetic dependency                |
| Convoy strip / sheet    | Convoy + role -> invite/follow/leave/end                 | Preserve leader rules and edge indicators         |
| Avatar base             | Permitted image/initials + label                         | Image fallback and accessibility                  |
| Avatar frame            | Approved style + motion policy                           | Pure decoration; never authority or visibility    |
| Membership badge        | Effective tier + visibility preference                   | Separate optional Supporter badge                 |
| Driver feed adapter     | Bounded original posts + paging + account access         | Driver API/data/roles, not chat messages          |
| Motion policy           | OS reduced motion + lifecycle + power                    | Shared policy per native app, static fallback     |

Do not copy full admin capabilities into mobile or create a parallel profile.
ADR-003's canonical `users` and `vehicles` remain the only shared identity/garage.
Keep avatar, cosmetic frame, membership badge, map position, and game state
separate. A decorative frame must never imply verified identity.

## Android chat reading density (S29–S33)

Prefer more reading space over decorative vertical padding. Keep the existing
16 dp side margins, 16 sp message text, source/channel tabs, quick reactions and
48 dp action targets. The Android community/convoy body uses 8 dp vertical outer
padding and 8 dp gaps between the reading viewport and composer rows. The source
selector retains a 4 dp gap below it; its tabs keep their 48 dp targets.
Message bubbles may use up to 320 dp of the available width, with 8 dp vertical
and 16 dp horizontal text padding and a minimum 48 dp long-press target. Text
scaling, keyboard/system insets, replies and moderation remain unchanged. These
Android layout refinements do not alter iOS implementation or chat behavior.

## Driver feed inside Chat

Use a native adapter to actual Driver APIs and `driver*` data. Each post/detail
shows **Från Driver** / **From Driver** beside author/time metadata, not as a logo
or banner. Opening a post opens the original post detail/comments, not a DM.
Keep page size bounded at 20 and retain target IDs and cursor semantics.
Shared UID does not bypass Driver role, suspension, deletion, block, or moderation
rules. Separate feed activity from chat unread counts. Cancel old requests and
clear content on account change; reject stale responses from the previous UID.

Start with real readable feed/detail. Only expose create/like/save/follow/report
actions when the original supported API and its error/retry/authorization
behavior have been implemented. No inert action buttons, fabricated posts, or
silent automatic terms acceptance. If Driver onboarding or a backend deployment
is required, show an accurate recovery state and record it in the validation
report. Do not deploy anything as part of the design PRs.

## Crown Hunt and membership invariants

The Crown Hunt hub has Overview, Statistics and, when enabled, Shop as its
primary tabs. Return to the map through the shared Back control; do not add a
separate Map tab or duplicate the shop link in Overview. Measure complete labels
at the user's text scale, adapt tab widths/padding to the viewport, and keep
labels on one line. For accessibility overflow, scroll horizontally rather than
shrink, wrap or truncate labels. Retain a minimum 48 dp Android hit target.

Preserve separate curated-point and spawned-crown flows, participation choices,
stats/history, seasons, inventory and trap/shield/boost behavior. Feature flags
control the whole relevant surface immediately, including an open sheet.
Do not change defaults or resurrect legacy paid participation gates.

Server rewards use `pointsAwarded`, including clipped daily allowance. Present
out-of-range, waiting for position/stillness, ready, sending, collected/already
taken/expired, daily limit, GPS and risk failure as distinct states. A duplicate
claim must not replay a reward. Common/uncommon are shared; rare/legendary are
exclusive according to executable backend behavior. Reference base values are
10/25/100/500 KP; runtime rules and server responses remain authoritative.

Community / Plus / Supporter retain 2/5/10 vehicles, newest five / 90 days /
unlimited drive access, and 2250/3000/3000 daily Crown Hunt KP. History access
restriction never deletes older drives. Event details/RSVP, chat/friends/DM,
convoys, saving and statistics remain free under existing account rules;
check-in/attendee names and member partner offers retain paid authorization.
Display store-localized prices. Existing live-map access is not redefined here.

## Motion and accessibility

Source timings are targets, not new backend clocks: tap 80-120 ms, sheet
220-300 ms, search expansion 180-220 ms, reward 600-900 ms after confirmation.
Reduce motion uses an immediate state change or a short 100-150 ms fade with the
same text. No continuous loops behind an opaque panel or in the background.
Prefer no decorative motion by default when power/reduced-motion state is
uncertain. At most three decorative map animations; none generate network/GPS
calls. Removing a stopped/hidden driver is immediate and never waits for a fade.

Use scalable text, accessible names/roles, focus restoration, state text/icons in
addition to color, and visible retry. Test 360/390/430 logical widths, long
Swedish/English text, keyboard and system insets, large text, both themes,
TalkBack/VoiceOver and reduced motion. Native OS chrome is not drawn into assets.

## Delivery and verification

The shared contract/docs PR is followed by separate Android and iOS PRs. Keep a
screen-by-screen status in [the migration register](mobile-redesign-screens.md)
and actual evidence in each platform validation report. Foundation coverage is
not proof that all 84 screens match. Every follow-up feature must reference its
screen IDs, semantic tokens, module boundary, state variants, and native tests.

Required automated checks: contract/schema validation, both generators with no
drift, existing navigation/route restoration and map control tests, relevant
chat/Driver state and accessibility tests, Android stub and navigation variants,
lint and release shrink, and iOS simulator unit/UI tests plus unsigned device
Release build. Capture reference and rendered screens for visual comparison.

Required configured-device checks before release: real map/search, GPS fresh and
stale, live start/stop/hide, background/foreground, convoy and block/account
transitions, crown claim/duplicate/budget and Stockholm midnight/DST, existing
store purchase pending/cancel/restore/revoke, and performance on an affordable
Android phone and an iPhone. CI builds alone do not prove those scenarios.
Use isolated PRs. The owner has authorized merging only after successful checks
and addressed review findings. A code merge is not release approval: no store
upload, backend deployment or production flag change is implied. Keep current
executed results separate from historical pending notes in platform reports.

## Later cosmetic delivery

A free preview frame can establish the renderer, but ownership, grants, equip,
catalogue, payment/restore/revoke and cross-account lifecycle need a separate
reviewed contract. Cosmetics never improve KP, discovery radius, exposure or
identity status. Permanent purchases and membership-borrowed styles need
different expiry policies. Keep the cosmetic store separate from the KP perk
store. No new cosmetic price or paid product is authorized by these mockups.
