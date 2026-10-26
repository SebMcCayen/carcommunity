# Native redesign visual acceptance

final result: blocked

This is a production native-app migration, not a web prototype. The user asked
to begin integration in separate Android and iOS PRs. This report tracks visual
acceptance independently from implementation, compilation and unit tests.

## Evidence and comparison target

- Source visual truth: `docs/design/references/2026-10-24/`, exported from the
  supplied 84-view catalogue; source hashes and view IDs are recorded there.
- Source image sheets inspected: S01-S84. Each sheet contains four concepts;
  sample people, cars and map locations are not production fixtures.
- Implementation screenshots: native shell captures have been opened alongside
  the sources in the iterations below. This is limited config-less evidence,
  not acceptance of the complete catalogue. Platform capture harnesses belong in
  `docs/design/android-redesign-validation.md` and
  `docs/design/ios-redesign-validation.md` in the platform PRs.
- Target: the app-owned viewport and matching theme/state. OS status bars,
  keyboards, safe areas and navigation chrome stay native. Normalize source
  crops and screenshot density before judging layout; do not compare the full
  four-view sheet against a single device screenshot.
- Full-view and focused-region comparison: performed for the initial shell
  states below; matching-viewport, populated-content and full-catalogue
  comparisons remain pending. No complete screenshot equivalence is claimed.

## Required fidelity surfaces

| Surface              | Acceptance evidence still required                                                                                 |
| -------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Fonts and typography | Native system family, hierarchy, wrapping, large text and Swedish/English in rendered captures                     |
| Spacing and layout   | App-owned crop, search/rail/tab geometry, keyboard/insets, narrow and wide screens                                 |
| Colors and tokens    | Actual dark/light rendering; schema/codegen and text-role contrast checks alone do not prove visual fidelity       |
| Images and icons     | Real permitted avatars/vehicles, native icons, actual configured map; synthetic QA fixtures are not asset approval |
| Copy and content     | Reachable actions, accurate empty/error/restricted states, source label, preserved membership/privacy rules        |

## Findings and blockers

- [P1] Complete rendered acceptance is not established on both platforms.
  Initial source/native shell comparisons are recorded below; extend them to
  matching populated states, themes, viewport sizes and text scales before
  claiming the full design is verified.
- Android wave 2 now implements five detailed feature areas in the migration
  register, beyond shared tokens. Full rendered acceptance of all 84 concepts
  still remains open. Paid profile-frame purchases are deliberately deferred;
  Driver reading is included, without unsupported writes or a fabricated link.
- Config-less screenshots can establish shell layout/state only. Real map
  markers, search, Driver data and live/convoy/crown behavior require configured
  integration checks before a production release.

## Implementation checklist

1. Build each native platform and execute navigation/account-state regressions.
2. Capture matching source/native states with viewport, pixel dimensions,
   density, theme, locale and text scale recorded.
3. Compare full view plus focused regions; fix P0/P1/P2 differences and recapture.
4. Record each comparison iteration and remaining configured-device gaps in the
   relevant platform report. Change this result only after both lanes qualify.

## Comparison history

### Android pass 3 — 2026-10-26

The [pass-3 evidence report](docs/design/android-redesign-pass3-evidence.md)
and [selected native packet](docs/design/evidence/2026-10-26/README.md) record
the map docking/camera and detailed screen work separately from the historical
wave-2 checks. They pin source/APK/file hashes, locale/theme, logical/physical
viewport and text scale. All source sheets were reviewed; native overview and
focused same-family comparisons exposed and corrected control density, action
shape, editor exits, identity wrapping and large-text footer issues.

100 primary local component-state captures plus two final convoy variants are
not acceptance of all 84 routes. The configured map can render base geometry,
but annotation/label/GPU composition remains a phone gate; an earlier configured
multi-map batch timeout is not counted as a pass. Current native/JVM/lint checks
belong in the platform index, while full authenticated/device/iOS acceptance
remains blocked here. No version bump or deployment is implied by this report.

### Current Android wave 2 — feature previews, 2026-10-25

All source sheets S01-S84 were inspected; relevant states were checked against
the native previews, including focused hierarchy, controls, spacing and copy.
Canonical native Roboto/system typography and real data contracts take
precedence over illustrative sample people/cars and non-native mockup fonts.
This is a qualitative component-state review, not a pixel-equivalence pass:
the source concepts and 320 × 640 CI viewport are different sizes/states.

Run `40187162028`, app `2f421ed1`, provides twelve actual API 34 dark,
English/normal-text component captures. Swedish local test content and the
visible test-data banner prevent them being mistaken for real member data.
Every filename was checked against its pixels after the stale-frame correction.
No full populated map, real car photos or real Driver account is represented.

Observed matches in these states: graphite reading surfaces, gold primary and
Crown accents, cyan chat/source/live roles, explicit headings, contained text,
visible event RSVP controls, grouped settings and accurate Community/Plus plan
copy. Long pages are scrollable; a screenshot's lower edge is not its final row.

Two concrete findings were corrected instead of counted as a pass:

1. Some first-generation screenshots (`40186384885`) showed the preceding
   window. They were rejected. The selected Compose root now completes a draw
   before capture (`2f421ed1`); the recaptured files match their intended states.
2. [P2] S57 garage management buttons made one list card consume nearly the
   whole short viewport. PR #1182 (`5d019ca1`) compacts the photo to 2.5:1 and
   moves actions into a car-named accessible menu. Correct-car callbacks,
   separation from opening detail and deletion confirmation have regression
   tests. Final recapture is recorded in the executed evidence log.

The final `7c2e64de` garage image resolves that density finding in the captured
320 × 640 state: both test cars, their separate menus, the plan counter and Add
vehicle fit in view. All twelve final files were opened, matched to their names
and retained unchanged in the gallery with hashes. Run `40188008306` passed all
100 emulator cases, unit/lint/debug/R8/alignment and navigation compilation.
These green results do not change the full-acceptance limits below.

Final combined app revision: `7c2e64de`. Its final check/capture provenance lives
in [the executed log](docs/design/redesign-executed-evidence.md), separately from
the earlier green checkpoint. Full 360/390/430 logical widths, Swedish/English,
light/dark, enlarged text, keyboard/TalkBack and configured account/GPS/billing
states remain required. No claim of complete 84-view acceptance is made.

The earlier local emulator OS/ANR failure remains rejected evidence. The report
intentionally stays **blocked for complete visual/release acceptance**, even
when implementation and automated checks are green. No new iOS behavior is
implemented by this wave; its historical comparisons below are preserved.

### Historical first-slice checkpoints

Initial checkpoint: sources inspected; schema/code generation and contrast
checks performed. Native visual comparison remains pending. PRs are reviewable
implementation checkpoints, not a claim that the redesign is ready to release.

### iOS capture iteration 1

Run `40131037243`, implementation `6d6705db`, produced native iPhone 17 Pro
attachments. The source S29 sheet (1649 x 954 pixels, four concepts) and the
1206 x 2622-pixel app capture were opened together. The device viewport is
402 x 874 points at 3x density. Native OS chrome is excluded from app-content
judgments; the four-view source sheet must be cropped before a pixel-level pass.

The attachment named `configless-sv-Dark-default-S29-chat-opaque` rendered a
light reading surface, not the requested dark state. A focused header/navigation
review also found the heading naming the selected Community channel rather than
the Chat area, and a cyan primary-navigation selection instead of the source
gold role. These are P1/P2 acceptance findings, not density differences. The
iOS owner is correcting the fixture/theme application and header/navigation
roles; fresh captures of the revised implementation are required.

Evidence is in the `ios-configless-screenshots` artifact for the run, with a
manifest mapping names to PNGs. Reviewed Chat PNG:
`A0086558-CF3B-4A8C-B205-974DBE2FADE5.png`; map PNG:
`998BB27F-0175-47D1-BBAA-1481DCF58630.png`. The map is a deliberate config-less
placeholder, so real map-image/marker quality remains unassessed. This iteration
does not pass dark-theme fidelity or the overall visual gate.

### iOS capture iteration 2

Run `40132485529`, code `ccf2b4e8`, supplied corrected dark screenshots. The
source sheet and native captures were opened together, including focused
header/selector/navigation inspection. `040CB5D9-59B0-46F2-A22E-86550AA5BC7D.png`
shows actual graphite surfaces, the Chat heading and gold primary-navigation
selection. `C5AE18F8-4BA7-42C7-98D2-BF2A249C1AB6.png` confirms the actual native
keyboard and focused address input. The earlier theme/heading/color findings
are resolved in these visible states. The map rail was still centered in that
capture; code `0bac8e00` moves it below the top chrome and needs fresh evidence.

Fonts remain native SF, with legible hierarchy and state text; dark/light roles
are contract-based. Actual map imagery, avatars and populated Driver content
remain unavailable in the config-less fixture. Detailed feature layout fidelity
and complete same-viewport normalization remain outside acceptance of this
initial shell slice, so the overall result stays blocked.

### Android capture iteration 1 and CI recapture

Local 390-logical-pixel S01/S02 images were rejected: a Pixel Launcher ANR dialog
covered/dimmed the first image and prevented a normal keyboard state. These are
environment-contaminated captures, not evidence of an app design pass.

CI run `40134452073`, code `753257b7`, retained S01/S02/S29/S53 PNGs after test
app cleanup. Source and native images were opened together. Full-view and focused
chrome checks show the corrected upper tool rail, labelled primary navigation,
gold selection, explicit Chat heading and opaque reading surface. The drive
list is clearly a synthetic test fixture. CI's narrow default viewport differs
from the reference; this is functional/narrow-layout evidence, not a 1:1 source
pixel pass. Roboto/system text and token colors are legible. No logo or Chat
circle was added back to the map, and no reference photo/person was fabricated.

That CI run passed 98/99 UI cases. The remaining legacy test incorrectly looked
for visible map chrome underneath the newly opaque Chat page. Code `a4d11153`
instead asserts a single retained map instance, paused rendering, absent covered
chrome and successful reactivation after Back. Run `40135320682` passed all 99
UI cases, with no failed/skipped cases, at `a4d11153`. Lint, debug/release unit
tests, the R8 release build, alignment and navigation-SDK compilation also passed.
Basic shell captures do not complete
all 84 feature layouts or configured-device safety/billing/Driver checks.

### iOS capture iteration 3

Run `40134626295`, code `0bac8e00`, passed simulator tests and the unsigned
generic-device Release build. Source and final native captures were opened
together. Swedish dark map `ED212E16-864E-44DF-B9E5-6DC0A5A77D6F.png` and
English light map `A635375D-D13F-44EA-9120-E01F05749513.png` confirm the tools
now start below the top chrome. Swedish dark Chat
`D52BCCFF-AB35-49F3-A89F-CC78BCE2C0A8.png` confirms the source selector,
opaque reading surface and gold navigation role. Config-less Settings replaces
the signed-in avatar, and unavailable content is not a populated-content pass.

The English accessibility-XXXL Chat capture
`428835A2-B935-4E16-B0C0-5EE575BFBEE7.png` exposed a **P1 layout failure**:
primary-navigation labels break across fragments, compact chrome occupies too
much height, and the unavailable-state body truncates. Passing XCTest assertions
did not establish usability at that text size. The iOS owner is fixing this
responsive/accessibility regression with stronger checks and fresh captures.
Overall visual acceptance remains blocked.

### iOS final accessibility recapture

The intermediate run `40137029824` at `8b8e89d3` revealed a second concrete
failure: a primary button's accessibility/hit width measured only 33.3 points,
and an accessibility-size Chat tap did not select the tab. The 44-point check
was retained. Commit `47570be2` gives the complete 56-point-high framed label a
rectangular content shape; `197b8970` also fixes the menu glyph at 22 points.
Only compact navigation chrome is bounded; reading content retains full user
text scaling and a naturally sized scrollable recovery message.

Final run `40138657535` at **`197b8970` passed**: 1,201 XCTest unit tests,
17 Swift Testing tests, all nine UI tests, and the unsigned generic-device
Release build. The source sheets and fresh PNGs were opened together:

- `C2F1FEB8-B995-43F4-B2ED-7F8382FD7A88.png`: accessibility-XXXL Chat has compact
  navigation, readable source controls and the complete unavailable message,
  without an ellipsis. Reading text remains visibly enlarged.
- `717F4E92-4E5C-42D7-9BE8-44C0341DA9DE.png`: horizontally scrolling the channel
  strip exposes and selects Notifications; the existing route is not removed.
- `492A66E3-8DA7-4B5A-9308-CC52C109D4C3.png`: accessibility-size map chrome has
  contained decorative glyphs and the compact bottom bar. The map is a stub.
- `7AA02D9C-4247-4C70-843E-663BC880F388.png` and
  `39A5E5AE-66F5-4D48-B423-22D95D62BF77.png`: final English/light map and
  Swedish/dark Chat variants were inspected as well.

The initial shell accessibility P1 above is resolved in these states, with
rendered evidence and geometric/reachability regression checks. This does not
resolve the separate full-catalogue, populated-data and configured-device
acceptance requirements, so the report's overall result intentionally stays
**blocked**, not a release approval.
