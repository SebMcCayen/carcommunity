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
