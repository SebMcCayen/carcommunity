# Android chat labels and Saved places — 2026-10-28

The screenshots are actual emulator pixels from this change, captured through
`MapFirstShellTest.captureRedesignShellScreens`. The shell uses `StubMapSurface`
and local test repositories, with no real account, Firebase or Mapbox credentials.
An unavailable chat body and placeholder map are intentional fixtures; these
captures verify shell/menu geometry, not production map rendering or chat delivery.

- [Chat labels](chat-centered.png): all five channel labels are vertically centred.
- [Avatar menu](saved-places-menu.png): Saved places appears first in the existing menu.
- [Existing picker](saved-places-picker.png): the menu opens the existing saved-place picker.

The map-home right-side Saved-places disc is removed. Turn-by-turn retains its
shortcut because that mode has no avatar menu. Existing store, picker, navigation,
sharing, renaming and deletion flows are reused.

## Validation

- Targeted JVM/Robolectric: **9 tests, zero failures/skips**. The label test covers
  72 combinations of width 320/360/390 dp, font scale 1/1.5/2, Swedish/English,
  dark/light and presence/absence of the Back button. It measures text-line
  geometry as well as target sizes, width, clipping and unread badges.
- Debug APK and instrumentation APK compilation: passed.
- Android 35 emulator, 360 dp phone: **8 native tests passed**: map controls,
  actual label centring, avatar/picker navigation and Back, two convoy/footer
  geometry interactions, and saved-place management regression.
- The first native pass needed the repository's retry runner for two UI-window
  transitions (one transient missing Compose hierarchy, one asynchronous popup
  dismissal). A separate confirmation of both affected methods passed on their
  first attempts, **2/2**, and produced the screenshots above. No failed attempt
  was omitted from the assessment.

Physical-device acceptance, production account/network behaviour and
turn-by-turn live navigation were not exercised by this local fixture run.
