# Crown tile component evidence — 2026-10-26

`crown-marker-components.png` is the unedited native emulator display capture
from `CrownMarkerDesignCaptureTest`, API 34, 1170 × 2532 px at 480 dpi
(390 × 844 dp). The preview is explicitly labelled as components, not a map.
It draws the production `CrownMarkerBitmaps` at natural density with all four
rarities, out-of-range/ready states, and close packing on light/dark surfaces.
No account, location, map service or Firebase data was used.

SHA-256: `fa05043aaeecff8a24ab3aafc1f995a7155a8ced742cd655fccd9c04ac99844c`.

Validation completed:

- 364 focused crown/map JVM and Robolectric tests: zero failures/errors/skips.
- Pixel checks at five densities include compact bounds, transparent corners,
  solid body, rank-bar separation, range colours and absence of halo.
- Android lint, debug APK and instrumentation APK builds passed.
- Native frame-commit-fenced capture test: 1/1 passed in 2.283 s.
- Token schema validation passed; Android and Swift token generators ran.

Earlier attempts using Compose's AndroidView capture yielded stale/blank native
window pixels despite ready semantics. Those failed captures are not evidence.
The committed fixture uses a normal native view and a frame-commit fence before
asserting exact screen colours and preserving its actual display PNG.

The component capture does not establish live Mapbox annotation density,
physical GPS/stillness/claims, TalkBack, performance or iOS implementation
acceptance. Confirm these on a configured phone before release.

Curated admin points have no repeat metadata in the map model. A successful
map claim suppresses that point until next UTC midnight or map composition
disposal/account switch, whichever comes first. Once/weekly points can reappear
before eligibility; backend rules remain authoritative. Auto-spawn collector
suppression uses the existing durable UID-scoped cache/server reconciliation.
