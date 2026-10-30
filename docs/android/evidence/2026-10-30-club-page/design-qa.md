# Club page visual QA, 2026-10-30

final result: blocked

The code checks passed. Visual acceptance is held for inconsistent drawing in
some exported scrolled images, not for a confirmed source-level UI regression.
No code changes were made in response to this audit.

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

All captures are 1080x2200 native Compose content from the parent's 1080x2400
emulator. Source and normal-scale implementation have equal pixel dimensions;
no density normalization was applied. CSS viewport/deviceScaleFactor do not
apply to native Compose; a measured Android density was not recorded here.
Font scale 2.0 is an accessibility reflow comparison at the same pixel viewport,
not a density-scaled copy or a pixel-identical match to the 1.0 baseline.

State: the current production `SharedClubRoute` with local owner/closed-club
fixture, four members including one moderator, aggregate statistics and two
October events. The neutral local-test-data banner replaces the baseline's
BEFORE caption intentionally. Capture invocations do not execute mutations.

## Findings

### P2: incomplete scrolled capture content prevents acceptance

In `after-20261030-1_0/03-calendar-dark-sv-1_0.png`, dates 1–29 are absent;
only highlighted dates 30/31 remain. The back arrow, calendar heading and
subscription control are also absent. The corresponding accepted baseline and
new light capture show those elements. The same dark calendar dates are visible
in `02-statistics-dark-sv-1_0.png` and `03-calendar-dark-sv-2_0.png`.

Other normal-scale exports also omit chrome/heading content:
`02-statistics-light-sv-1_0.png` lacks its back arrow/statistics heading, and
`04-members-upper-dark-sv-1_0.png` lacks its back arrow/Members heading.
The 2.0 versions show those headings. This is not explained by intended text
color changes: current source explicitly supplies neutral `onSurface` for every
calendar day, including non-event days, and the headings exist in composition.

Impact: a missing calendar grid or navigation would be a user-visible defect
if reproduced on screen. Inconsistent export/drawing remains a plausible
explanation; source, passing semantic tests and other PNGs cannot establish it.
Do not promote this set as an unconditional visual pass.

Narrow follow-up, owned by parent: on the already-built APK, inspect the actual
scrolled dark calendar/members and light statistics states at scale 1.0; retain
a native screen capture alongside the Compose export. If the screen is correct,
replace the affected exports with settled captures. If the screen also loses
content, diagnose that rendering defect before changing styles. No broad
rebuild/test expansion or speculative product change is justified yet.

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
| Spacing/layout | Existing page margins, radii and back chrome remain the target. Identity/intro are separated; three metrics share one card; the four-member roster is compact; club management follows it. More vertical scrolling at 2.0 is expected. Missing drawing in the listed exports is the acceptance blocker. |
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

These are one-emulator local fixtures/fake-repository tests. No production
Firebase/Storage round trip, TalkBack session, physical phone, second device,
release or deployment validation is claimed. This agent ran no Gradle/emulator
jobs. No root-level report is added because this handoff is explicitly limited
to Android docs/evidence.

## Comparison history and remaining work

1. Baseline audit identified gold reading text, absent avatars and persistent
   management rows. Implementation and avatar follow-up are committed and tested.
2. First matched after audit found the inconsistent scrolled exports above.
   There has been no speculative code fix or accepted replacement capture.
3. Parent resolves the narrow screen/export discrepancy. Recompare replacements,
   then copy only identity-dark plus a complete members image into this evidence
   folder and update the result. Until then, final result remains blocked.
