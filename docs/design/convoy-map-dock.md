# Android convoy dock and collision boundaries

Scope: S16 map convoy controls and shared compact controls during Android
navigation. Preserve existing member/leader actions, invitations, chat, shared
destination, member profile/location actions and confirmation rules. No iOS,
backend or production-configuration change is included in the dock redesign.
The separate [Follow-me contract](convoy-follow-me.md) covers shared trail
leadership and its later functional verification.

## Map browsing

Use the approved map palette, gold accents, outlined rounded surface and native
48 dp touch targets. Statistics and convoy controls occupy separate rows of the
same measured dock above the persistent footer. The convoy action row flows onto
another row when width or text size requires it. Member count retains its full
spoken label and a full-height touch area. Keep leave/end role-specific and
confirmed; the convoy exit does not replace the centre stop-sharing control.

The rail and convoy-reaction layer reserve the dock's measured height, including
any additional statistics or control rows. Test physical control bounds rather
than fixed estimates. A scrollable rail keeps controls accessible on short maps.

Member lists and per-member reading actions use `MapDetailSheet` in the existing
`DockedMapSheetHost`, inside the registered map body. They must not open in a
window-anchored popup below the count or a separate full-window bottom sheet;
those can cover the footer after moving the convoy bar to the bottom. Long lists
scroll and retain the shared close/back affordances. Explicit destructive
confirmations remain dialogs. Footer navigation dismisses reading panels and
must accept an OS-injected tap while a panel is open.

## Active navigation

`NavChromeRegions` separates maneuver/live/convoy chrome from speed, map tools
and ETA/Exit in the real Navigation SDK source variant. The upper viewport is at
most 45% of available height; the lower viewport gets the remainder after the
upper viewport's actual measurement. Both scroll when necessary, with the lower
region initially showing ETA/Exit. Both are measured in one pass, so growth,
rotation and larger text do not briefly overlap. Unoccupied middle space retains
map gestures. Existing SDK engine/camera handlers and control actions are retained.

## Evidence limits

Use normal/compact, narrow/large-text and leader/member tests; verify touch sizes,
non-overlap and unchanged exit routing. Native S16 fixtures use synthetic local
members, a stub map and production Compose components, without GPS or backend
writes. Verify the roster/actions panel bounds and use OS-dispatched footer taps.
The shared navigation layout is tested with tall/scaled content and map-touch
passthrough. A tokenless debug build compiles the no-SDK navigation variant, so
these tests do not establish a running Navigation SDK route or physical-car QA.
