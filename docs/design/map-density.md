# Static map density

Android and iOS coordinate traffic incidents, speed/photo/weather camera sites,
and crowns in one presentation plan on the main map. Driver photos, event pins,
billboards and collection authorization remain owned by their existing features.

- Cluster within 56 logical world pixels at a retained integer zoom bucket.
  A 0.15 zoom hysteresis prevents boundary oscillation. Pan and rotation do not
  enter the membership calculation. Groups are anchor-bounded, not transitive
  proximity chains across an entire town.
- Group identity uses the smallest stable member ID. Selection changes emphasis
  and the displayed real member anchor, not membership or group identity.
- Critical hazards seed groups first. Mixed groups retain a warning TRIANGLE;
  selected groups retain a CHECK and outline. Meaning does not depend on color.
- Compact count is bounded to `99+`; the true total remains in accessibility
  labels. Every source member stays available through a docked chooser with
  20 rows per page, then the existing authorized detail. Opposing camera views
  remain available in the camera detail.
- Main-map camera sites bypass the legacy per-type grouping/draw cap, and crown
  source presentation keeps all uncollected rarities at every zoom. Existing
  local query bounds, feature/layer gates, expiry and account authorization hold.
  Both platforms hide the reader's confirmed collected crowns; gray uncollected
  out-of-range crowns remain represented and grouped.
- Android retains original source flows but removes covered native annotations
  and tap lookups. Queued covered annotation taps are consumed without opening
  an invisible original or a basemap place. iOS replaces the three competing
  static overlays with one hit-tested tree.
- Static group badges reserve 44 logical points around the actual rendered own
  puck, even while panned. A bounded leader (normally 44 points, at most 88 near
  an edge) marks the real geographic anchor. A missing own position is never
  replaced with the camera center.

## Acceptance

Inspect day/night, large text, TalkBack/VoiceOver, and ordinary/reduced motion on
both native platforms. Use synthetic colocated hazards/cameras/weather/crowns,
1,000 crowns, verified opposing camera directions, all pages of a large group,
selection while zooming/panning, source expiry while a chooser is open, and a
manual pan leaving the own puck away from camera center. No eligible source ID
may disappear from the chooser or open another member's detail. Grouped markers
are presentation substitutes, not missing original map content in diagnostics.
