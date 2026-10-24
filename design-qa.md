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
- Implementation screenshots: pending native capture and review. Platform
  capture harnesses and actual executed results belong in
  `docs/design/android-redesign-validation.md` and
  `docs/design/ios-redesign-validation.md` in the platform PRs.
- Target: the app-owned viewport and matching theme/state. OS status bars,
  keyboards, safe areas and navigation chrome stay native. Normalize source
  crops and screenshot density before judging layout; do not compare the full
  four-view sheet against a single device screenshot.
- Full-view and focused-region comparison: pending rendered evidence. No
  screenshot-equivalence or complete 84-screen acceptance is claimed.

## Required fidelity surfaces

| Surface              | Acceptance evidence still required                                                                                 |
| -------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Fonts and typography | Native system family, hierarchy, wrapping, large text and Swedish/English in rendered captures                     |
| Spacing and layout   | App-owned crop, search/rail/tab geometry, keyboard/insets, narrow and wide screens                                 |
| Colors and tokens    | Actual dark/light rendering; schema/codegen and text-role contrast checks alone do not prove visual fidelity       |
| Images and icons     | Real permitted avatars/vehicles, native icons, actual configured map; synthetic QA fixtures are not asset approval |
| Copy and content     | Reachable actions, accurate empty/error/restricted states, source label, preserved membership/privacy rules        |

## Findings and blockers

- [P1] Rendered acceptance is not yet established on both platforms. Open the
  source and matching native capture together, inspect full view and focused
  chrome/text regions, then record exact evidence and differences.
- Detailed feature-specific layouts remain in the migration register. A shared
  token change is not completion of all 84 views. Paid profile-frame purchases
  are deliberately deferred; Driver reading is included in the current slice.
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
