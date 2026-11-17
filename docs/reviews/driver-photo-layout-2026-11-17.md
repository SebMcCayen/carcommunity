# Driver photo layout — 2026-11-17

Screen S01 (map), Android and iOS. Implements the user-selected design 1:
40 dp/pt native car photos, true geographic position dots and short leader lines.
The user approved longer lines showing all photographs for dense co-location;
there is no count badge, list substitution or separate driver rail.

## Shared presentation contract

Nearby and convoy drivers share a single deterministic layout and the existing
motion timeline. Convoy wins duplicate UIDs. The actual rendered own position,
including during manual pan, reserves a 28 dp/pt radius; photographs have distinct
48 dp/pt hit bounds. Leaders are clipped around that free zone. Measured individual
map controls and the actual bottom dock exclude targets without reserving empty
space between controls. Source coordinates remain unchanged.

Normal placement uses sticky short slots (56/72 dp/pt). Dense placement packs
48 dp/pt viewport cells, removing extra gaps while keeping 40 dp/pt photographs.
Cached offsets move with their geographic anchors. Stable placement tokens permit
the separate transition PR to fade discrete slot changes without moving through
the own puck. A crowd that shrinks to 60% of its entry count (minimum two), or
spreads to at least 104 dp/pt between anchors, returns to short slots only when
all short placements fit with 8 dp/pt clearance. This avoids permanent long lines
once the crowd clears and adds clearance hysteresis.

Configured maximum of 74 drivers fits 390×700 and 390×844 map areas with actual
individual profile/search/tool/dock exclusions in pure geometry regressions.
Finite or unusually small viewports can still have explicit planner overflow;
this PR does not invent an unapproved alternative for physically impossible
packing. The supported roster-cap regression must remain green.

Runtime photos, existing car fallbacks, public membership rings and club logos
are retained. The iOS existing public profile listener now supplies the same
cosmetic membership-ring fields as Android without adding another listener.
Tap actions revalidate the current authorized UID before opening existing detail.
Privacy/roster removal removes markers immediately; offscreen convoy arrows remain.
VoiceOver/TalkBack names include the driver and cosmetic badge descriptions.
SV/EN membership ring description comes from the shared localization contract.

## Validation and design QA status

- PASS: Android production Kotlin compilation and 70 focused unit tests: geometry
  (13), smoothing (25), arrows (26), live layers (6). Debug APK and instrumentation
  APK assemble successfully.
- PASS: iOS read-only source preflight and 67 Python script tests.
- Pending: Android synthetic component capture (normal, dense, edge/manual-pan,
  exact UID tap and offscreen convoy arrow). Fixture compilation is complete.
- Pending: native iOS compiler/XCTest CI and simulator visual review. Linux has
  no Xcode; source checks are not native execution evidence.
- Pending: real-map driving/frame-time verification on physical devices. The
  isolated component fixture uses synthetic projection and production fallback
  icons, no auth/cloud calls; its own-position locator is a fixture icon.

References: user-selected normal and longer-line design 1 images from this task.
Generated reference photos/map screenshots are not runtime assets and are not
committed. Component captures must not be presented as full-map fidelity or
performance acceptance.
