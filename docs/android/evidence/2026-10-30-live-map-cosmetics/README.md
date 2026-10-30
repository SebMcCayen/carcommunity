# Live marker cosmetics — native Android evidence

Captured 2026-10-30 from production NearbyLiveOverlay and
ConvoyMapAwarenessOverlay with local projection/profile fixtures, on the owned
1080x2400 Android emulator. These are renderer screenshots, not a real Mapbox
basemap, real users, GPS traces or a two-phone production test.

[Light](light.png) · [Dark](dark.png). Both themes were also captured and
inspected at 2x font scale. Gold ring and club badge remain distinct from the
existing convoy direction/count and route rim; generic car/initials are the
intended fallback when fixture data has no photo/logo.

Parent validation:
- 17 new JVM tests passed in the combined 147-case run; its single failure was
  an unrelated club-page test matcher, addressed separately in that PR.
- Four native LiveMarkerBadgeOverlayTest cases passed: stationary server
  boolean/preference/club updates; bounded map/navigation listener handoff;
  lifecycle/account cleanup; preference saving guard.
- Four native capture runs passed: light/dark, font scale 1.0/2.0.
- Main, debug and native test sources compiled; both debug QA packages built.

Public profile eligibility/show preference only. Paid dates and the expiry
worker stay private in backend PR #1280. Nearby and convoy read only already
visible/authorized users; this is a cosmetic and never an entitlement check.
No version, release package, Play setting or iOS change is part of this work.
