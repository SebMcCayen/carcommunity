# Native message reactions — design QA

The owner selected `selected-reference.png`: a floating single-row frequent-emoji
menu above the held message, anonymous chips below its bubble, and the existing
message actions underneath. This selection supersedes earlier numbered options.

## Android evidence

`MessageReactionsCaptureTest` runs real native channel/chat/composer/reaction
widgets with synthetic messages and an injected in-memory transport. It never
signs in or contacts production. Android API 34 emulator, Swedish, dark and light
themes, 384 dp normal width and 320 dp narrow width passed the flow.

- Long press opens one row of at most five personal frequent emoji and plus.
  Narrow screens reduce the quick choices rather than wrapping the row.
- The toolbar leaves the selected author and bubble readable. Counts remain
  between the bubble and the action panel. The panel may cover adjacent history.
- Plus opens the full local catalog. Swedish `raket` search works with the
  keyboard visible. Searching/opening the picker makes no mutation request.
- Choosing the rocket makes exactly one mutation request, shows the confirmed
  own-selection chip and increments local history once. Reopening puts the
  historically used rocket into the quick row.
- The permanent composer emoji row is absent while enabled; composer remains.
- Labels, outline chips and menu contrast use the existing native theme palette.

Screenshots are in `android/`; `dark-personal-history.png` shows the updated row.
The normal dark screenshots include the contrast refinement; light screenshots
were captured before that dark-only color refinement. These component fixtures
do not prove authenticated full-app routing or physical-device behavior.

The existing native typography, chat grouping, theme colors and platform emoji
glyphs are retained. The reference's exact gradients, arrow and shorter action
copy are not copied as raster UI. Geometry adapts to the available viewport.

## iOS and review boundary

iOS implements the same native interaction, local catalog/ranking and server
contract. Source review covers all five chat integrations, safe action routing,
account-scoped cache, logout and background/foreground lifecycle. Build and unit
verification run in the PR's macOS CI. No iOS simulator screenshot or physical
iPhone interaction was captured on the Linux implementation host; visual and
VoiceOver device acceptance remains a release check.

No feature activation, merge, backend deployment or app release is part of this PR.
