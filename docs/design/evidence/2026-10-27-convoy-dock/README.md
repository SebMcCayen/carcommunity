# Android S16 convoy dock — 2026-10-27

Production Compose components hosted by local test fixtures, with synthetic
members/session data and a **stub map**. No account, live convoy, GPS stream,
backend write or production configuration was used. The S16 reference on page 7
of the supplied 84-view PDF was visually compared with the dock treatment.
Preserving existing convoy actions is intentional; this is not a claim that every
reference map marker, destination state or full screen is reproduced exactly.

| Screenshot | Variant |
| --- | --- |
| [Normal](convoy-normal.png) | Statistics and convoy controls above the footer; 100% text. |
| [Large text](convoy-large.png) | Statistics can grow while convoy controls retain their space; 200% text. |
| [Members](convoy-members.png) | Roster uses the shared reading panel above the footer. |
| [Member actions](convoy-actions.png) | Profile/location actions use the shared panel; 200% text. |

API 34 emulator, 480 dpi. Native geometry runs used 1170×2532 and 960×2532
viewports (390/320 dp widths), each at 100% and 200% system text size. Both
`ConvoyDockGeometryTest` cases passed at all four combinations: **8 native test
executions**. They assert statistics/convoy/footer separation, 48 dp control
bounds without overlapping targets, rail clearance above the measured dock, and
roster/actions panel bounds. Each dispatches a real OS touch to Chat while the
reading panel is open and checks both navigation callback and panel dismissal.
Compose semantics-only clicks are not used as proof that the footer is tappable.
Original emulator viewport and font settings were restored after the run.

**72 focused JVM tests passed:** 25 existing convoy-status action/confirmation
checks, 39 convoy-bar state checks, one control-layout matrix, five navigation
region checks and two responsive statistics tests. The control matrix includes
normal/compact bars, narrow widths, large type, a two-digit roster and capped
unread badge. Navigation tests cover short/scaled windows, growth/shrinkage,
scroll reachability, map-touch passthrough and actual live/convoy component
composition. Android lint passed; the tokenless debug application and instrumentation APKs assembled.
All four final screenshots were visually reviewed.

The navigation layout helper is exercised without the Navigation SDK. The
`src/nav` integration was reviewed as a wrapper-only change retaining contents
and handlers; a configured SDK build and an actual guided convoy route remain
separate checks. Rail/reaction clearance uses the existing measured dock height;
reaction networking, GPS movement and peer marker updates are not proven by
these static fixtures. iOS and release/version/deployment settings were untouched.
