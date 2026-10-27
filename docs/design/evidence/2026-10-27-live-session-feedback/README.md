# Android live-session feedback — 2026-10-27

These are native emulator screenshots of production Compose/Mapbox surfaces
hosted by `Wave2RedesignCaptureTest`. Local fixtures supply the session and save
states; the trail uses six synthetic Kungsbacka points. No real account,
production live session, backend write or stored drive was used.

| Evidence | Check |
| --- | --- |
| [Night trail](trail-night.png) | Gold core and dark casing remain distinct on the night map, below street labels; only the footer Stop control remains. |
| [Day trail](trail-day.png) | The same outlined trail is visible against pale roads. |
| [Saving, dark](saving-dark.png) | Pending text, car badge, Close and disabled History. |
| [Saved, dark](saved-dark.png) | Confirmed result, check badge and enabled History. |
| [Saved, light](saved-light.png) | Swedish copy, light surface and readable dark system-bar icons. |
| [Saved, enlarged text](saved-large.png) | Swedish dark screen at 1.5 font scale; controls remain visible. |

Map and dark feedback captures use an API 34 Pixel 6 emulator with host GPU,
1170×2532 / 480 dpi and English locale. Swedish light/enlarged captures use the
existing API 34 emulator with the same viewport. System animations were disabled
for deterministic stills. The enlarged font setting was restored after capture.

The configured-map harness requires native style loading, visible core/casing
layers, a completed native render and gold trail pixels within the map-centre
crop (excluding gold UI chrome), followed by visual inspection. The initial
software renderer failed Standard shaders at its 256-uniform limit; those
captures were rejected. A launcher ANR on the fresh host emulator was also
rejected and resolved before successful captures. Final night/day/light/large
instrumentation runs all reported `OK (1 test)` without an overlay.

Verification completed locally:

- Nine save-screen tests: pending/confirmed semantics, callbacks, dismissal,
  disabled History, animated transition without invented timed success,
  same-frame stop rejection and protection of a save already under way.
- Twenty-nine shell-navigation/map-cover tests, including pausing the map behind
  the opaque result surface.
- Focused drive recording/save, optimistic stop, breadcrumb and Follow-me suites.
- Android lint, debug application and instrumentation APK assembly.

Still-image captures do not demonstrate animation timing; Compose tests exercise
that transition with a controlled clock. A physical-phone GPS drive and real
stop→backend-save round trip remain device checks. These are debug test artifacts,
not a signed release or proof of Play/Firebase deployment.

## Follow-up: full-width, adaptive statistics dock

[Normal text](stats-width.png) and [200% text](stats-large.png) show the actual
production statistics bar in its map/footer fixture. These two captures use the
local **stub map**, not configured Mapbox: they verify dock geometry and text,
not map rendering. Inputs are synthetic 12:34 / 54 km/h / 12.4 km. Both native
capture runs passed and screenshots were visually reviewed; font scale was
restored to 1.0 afterwards. The footer's separate enlarged-label behaviour is
outside this statistics-only follow-up.

The bar now distributes each row's available width between the natural-width
metric cells. It adds rows instead of ellipsizing numbers or reducing type size.
Twenty-three focused JVM tests passed: the two layout tests include 18 width /
font-scale / short-or-long-value combinations (280/350/600 dp, 100/150/200%)
and a 72 dp-high scrollable viewport; the remaining tests cover elapsed formatting
and current-speed behaviour. Glyph bounds, full text offsets, original typography,
row width coverage and reading order are asserted. The native TalkBack-order
semantics test also passed. Lint and debug/instrumentation assembly were rerun.
