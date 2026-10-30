# Club page visual QA, 2026-10-30

final result: passed

Signed off for the accepted local native fixture states, including the previously
reviewed 2.0 font reflow. No actionable P0/P1/P2 product mismatch remains in
those accepted states. The capture discrepancy is resolved for visual comparison
by complete native evidence; some diagnostic outputs remain incomplete and are
explicitly rejected below. No production UI code changed during this diagnosis.

## Comparison evidence

Source truth is the approved existing native design system and the requested
club-page hierarchy, supported by the ten accepted baseline captures from
`/home/sebmccayen/Downloads/CarCommunity-live-club-qa-20261030/baseline-20261030/`.
Baseline code is `6756e8f`; use the suffixed light identity image
`01-identity-light-sv-1_0 (1).png`, excluding its rejected unsuffixed predecessor.

Implementation code is `4f7d48efb612084780d3921dd047a11b7e2d101d`. Opened the
twenty after PNGs in these directories and compared the five sections in both
themes with the accepted baseline:

- `/home/sebmccayen/Downloads/CarCommunity-live-club-qa-20261030/after-20261030-1_0/`
- `/home/sebmccayen/Downloads/CarCommunity-live-club-qa-20261030/after-20261030-2_0/`

Full-view comparison inputs included before/after images together. Focused
inspection covered the calendar date grid and section heading/back/subscription
area, member names/roles/overflow, and outlined action labels. These details
were readable in the original captures; no synthetic replacement UI was used.

The original before/after Compose exports are 1080x2200 content captures.
The diagnostic native-window and independent adb captures are 1080x2400, including
Android status/navigation bars. Compare their app-content region with the source;
no image resizing, invented pixel equivalence or density normalization is used.
CSS viewport/deviceScaleFactor do not apply to native Compose; measured Android
density was not recorded. Font scale 2.0 is a reflow comparison at the same pixel
viewport, not a density-scaled copy or pixel-identical match to scale 1.0.

Resumed audit: opened all ten images from
`/home/sebmccayen/Downloads/CarCommunity-live-club-qa-20261030/settled-window-20261030/`
and all three PNGs from the sibling `adb-paused-20261030/` directory. The accepted
normal-scale state set uses the following exact filenames (all suffixes
`-sv-1_0.png`):

| Step | Accepted light state | Accepted dark state | Health |
| --- | --- | --- | --- |
| 01 Identity | settled-window/01-identity-light | settled-window/01-identity-dark | Name/logo/intro/closed state separated and readable. |
| 02 Statistics | adb-paused/02-statistics-light | settled-window/02-statistics-dark | Back/title, three metrics and privacy note complete. |
| 03 Calendar | settled-window/03-calendar-light | adb-paused/03-calendar-dark | Back/title/subscription, all dates 1–31 and event text complete. |
| 04 Members upper | settled-window/04-members-upper-light | settled-window/04-members-upper-dark | Back/title/invite plus four avatars/names/roles and applicable menus complete. |
| 05 Members lower | settled-window/05-members-lower-light | settled-window/05-members-lower-dark | Remaining roster and grouped club controls complete; top row crop is expected scroll position. |

The table abbreviates the full directory names above. Committed after images
are unmodified settled-window identity-dark and members-upper-dark. The requested
adb members candidate is not substituted silently: it is rejected because its
banner/back arrow and part of Invite's outline remain absent. The settled-window
members capture is complete and is the accepted representative instead.

State: the current production `SharedClubRoute` with local owner/closed-club
fixture, four members including one moderator, aggregate statistics and two
October events. The neutral local-test-data banner replaces the baseline's
BEFORE caption intentionally. Capture invocations do not execute mutations.

## Findings

### Resolved P2 evidence blocker: complete native comparison states obtained

The initial normal-scale Compose exports omitted dates 1–29 in dark Calendar,
plus back/heading content in light Statistics and dark Members. The first audit
correctly held acceptance; source and semantic tests alone were insufficient.

Parent added test-only native-window capture, a 250ms real compositor settle,
and optional eight-second named-state pauses for independent adb screenshots.
The app implementation and local fixture content did not change. The accepted
adb Statistics/Calendar and native-window Members now visibly contain the missing
content at the same scroll positions. This resolves a persistent layout/color
regression as the explanation for those missing elements in the original images;
no speculative recoloring or production UI fix was made.

Capture limitation remains: not all diagnostic PNGs are complete. Reject
`adb-paused-20261030/04-members-upper-dark-sv-1_0.png` (missing banner/back and
partial Invite outline), `settled-window-20261030/02-statistics-light-sv-1_0.png`
(missing banner/back), and `settled-window-20261030/03-calendar-dark-sv-1_0.png`
(missing banner/back and part of heading, with compressed date drawing).
Each has a complete same-state counterpart in the accepted table above. These
files must not be presented as accepted screenshots or proof of uniform capture
reliability. The evidence supports capture/compositor timing as a limitation of
this instrumented run; it does not identify the underlying graphics mechanism or
certify absence of transient rendering outside the accepted captures.

### Existing outlined-button treatment, not a demonstrated regression

The new Invite/Edit/Upload labels are gray and can read as low emphasis.
Accepted baseline `03-calendar-dark-sv-1_0.png` already has the same gray
Subscribe label/border, and baseline `05-members-lower-dark-sv-1_0.png` has gray
Disband/Leave labels. Baseline Disband uses `enabled = !busy`; gray pixels alone
therefore do not establish a disabled state.

Source comparison: Invite/Edit retain their default `OutlinedButton` colors;
`ClubLogoControl`, `ClubCalendarSubscriptionControl` and `KccTheme` are unchanged
from the baseline. Theme typography sets sizes/weights without an explicit text
color, so a typography color overriding `LocalContentColor` is not supported.
`KccOutlinedButton` also delegates to default outlined colors. The committed
Subscribe entry itself has no `enabled` argument; readiness/dialog availability
must be distinguished from the appearance of that entry control.

Classification: inherited component treatment, with optional P3 affordance polish
for a separate design-system decision. No scoped recoloring is proposed. Gold
is still present in the event-day highlight and existing crown artwork; neutral
reading text implements the approved brief without removing the brand tokens.

## Required visual surfaces

| Surface | Result in readable/complete captures |
| --- | --- |
| Typography | Existing native family/type scale retained. Name/value hierarchy is clearer; white dark names/body and muted roles/labels separate reading priorities. At 2.0 long names wrap, overflow remains beside the row, and metric values stack without horizontal collision. No new font asset or measured font-metric equivalence is claimed. |
| Spacing/layout | Existing page margins, radii and back chrome remain the target. Identity/intro are separated; three metrics share one card; the four-member roster is compact; club management follows it. More vertical scrolling at 2.0 and partial top rows in lower-roster scroll states are expected. Accepted native states restore the missing chrome/date evidence; rejected outputs are excluded. |
| Color/tokens | White/ink reading text on navy/white surfaces replaces inherited gold body text. Event-day tint retains a gold accent; destructive calendar/club actions use the existing error token. Gray outlined actions predate this patch. No automated contrast certification was performed. |
| Images/icons | Existing fixture drawables render in circular member images; three-dot overflow stays aligned. No generated/new asset or custom replacement icon was introduced. Some white fixture artwork has weak contrast against the pale light avatar/logo backing; this local asset choice does not prove live profile-photo contrast or production Storage hydration. |
| Copy/content | Name, introduction, closed state, roles, aggregate privacy explanation, calendar entries and logo guidance remain readable where fully captured. Swedish resources coexist with English month/day formatting and number separators in this fixture; this existed in baseline and is not full locale certification. |

## Validation and limits

Parent reports 131/131 filtered JVM tests including eleven avatar cases; lint
and both QA APKs passed. Logs confirm BUILD SUCCESSFUL, eleven actual native
behavior tests, and two successful capture invocations; see the main audit for
paths. Shared behavior cases cover profile taps, role menus, confirmations,
ordinary-member restrictions, cap/busy/error/revocation and enlarged text.
The parent-supplied profile RouteHost callback is committed in `d2b8806`.
The final test-only fixture diff was reviewed and included unchanged with explicit
parent authorization: `clubPageCaptureMode=window`, 250ms settle, optional
`clubPagePauseAt` names and an eight-second marker/pause. It adds no account,
network, production UI or cloud operation. Parent's test APK build passed in 38s
(`/tmp/carcommunity-club-window-settle-build-20261030.log`), and the settled native
run passed one test (`/tmp/carcommunity-club-settled-native-20261030.log`).
External uiautomator XML files were not parseable and are not semantic evidence;
the eleven existing native behavior tests remain the semantic interaction proof.

These are one-emulator local fixtures/fake-repository tests. No production
Firebase/Storage round trip, TalkBack session, physical phone, second device,
release or deployment validation is claimed. This agent ran no Gradle/emulator
jobs. No root-level report is added because this handoff is explicitly limited
to Android docs/evidence.

## Comparison history and implementation checklist

1. Baseline audit identified gold reading text, absent avatars and persistent
   management rows. Implementation and avatar follow-up are committed and tested.
2. First matched after audit was blocked by inconsistent scrolled exports.
3. Parent produced native-window/independent adb evidence with test-only timing
   diagnostics. All thirteen new PNGs were inspected; complete counterparts for
   each suspect state are identified above. No product UI fix was needed.
4. Accepted identity-dark and members-dark copies are committed with this report;
   copied bytes match their native-window sources. The originally requested adb
   members copy is deliberately excluded as incomplete.
5. Final static visual comparison passes within the stated fixture scope. No
   additional product code change is requested. Keep inherited outlined-button
   affordance polish separate; keep opening each future capture before accepting
   it. No claim is made that the complete diagnostic run is artifact-free.
