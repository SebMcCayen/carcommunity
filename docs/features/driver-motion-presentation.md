# Driver motion presentation — Android and iOS

Applies to the user's own active drive, standalone nearby drivers and convoy
members. Presentation never changes accepted recording vertices, saved route
statistics, uploaded coordinates, crown eligibility or location authorization.

## Common timing policy

| Setting | Both native clients |
| --- | --- |
| Moving live publication | Nominally every 3 seconds |
| First observed stop | Publish promptly, once; then sparse stationary cadence |
| Stationary heartbeat | 180 seconds |
| Presentation time | 250 ms behind the latest available source timeline |
| Correction of a newly received fix | At most 250 ms, starting at the displayed point |
| Prediction horizon | At most 3.5 seconds after the physical source sample |
| Render cadence while moving | Up to 60 updates per second |

The reader carries the source sample timestamp, reported course and speed. It
uses source timestamps for ordering and quality checks, and a local monotonic
clock for display frames. Sample age at receipt maps the source onto the local
clock. Packet arrival never restarts an old fix's prediction horizon. Undated,
future-dated or already-old samples do not start velocity prediction.

Fresh, finite reported speed and course drive short geodesic prediction. With
older publishers that omit those fields, a velocity can be estimated from two
accepted positions no more than 5 seconds apart. Impossible speeds and invalid
coordinates do not generate a forecast. An explicit zero-speed update overrides
previous estimated velocity. After the prediction horizon, the marker stops;
we never keep inventing travel through a tunnel or a network outage.

The same policy is used for all driver kinds, including self. This equalizes
**application presentation**, not transport latency: a remote packet can still
be older than an on-device sample. A three-second publication interval is not a
promise of three-second end-to-end delivery. Legacy five-second publishers can
still leave a short freeze after the prediction horizon.

## Geographic and visual ownership

Nearby and convoy views interpolate geographic positions before projecting them
through the current camera. SwiftUI must not separately animate screen offsets:
that creates motion on a different timeline than the rotating/panning map.
Existing photos, dimensions, touch targets and identity/authorization sources are
retained. Collision layout is a separate change.

On Android, accepted recording vertices still own the private breadcrumb. The
recording presentation advances the puck, visible tail endpoint and follow
camera together. The predicted endpoint is a presentation-only line extension,
never a new recorded vertex. The existing provider disables additional Mapbox
puck animations for these already-rendered frames.

Mapbox Maps iOS 11.26.0 has a fixed 1.1-second internal location interpolator and
no public duration option. Feeding already-interpolated frames into it would
add a second delay. The iOS renderer therefore uses the SDK's existing default
puck shadow, bearing and top image assets in native point annotations, with no
new puck artwork. Its camera and private breadcrumb end at the same presented
coordinate. The app foreground location provider is consumed only while the map
is visible, active and authorized; hiding the map terminates that demand.

The resume policy consumes a physical source fix once, not every predicted
frame. Its one-time return transition hands off to the current presentation
point. Manual browsing, route previews and convoy focus keep camera ownership.
Reduced motion disables the decorative return transition; essential geographic
tracking remains continuous.

## Bounded limitations and verification

Prediction cannot know a turn or stop before the next physical sample arrives.
A three-second source interval trades lower publication cost for a bounded
course error around a corner; the next sample corrects it within the short
correction window. A stop update is sent as soon as the provider observes it,
which is distinct from knowing the exact physical instant a car stopped.

Automated scenarios cover straight driving across three-second publications,
continuous retargeting, explicit stop, a turn, north/antimeridian wrapping,
duplicate/out-of-order fixes, unknown/future source time, stale arrival and a
hard end to prediction. Android regression tests preserve the accepted route
prefix and exact equality between its visible endpoint and puck/camera input.
Native iOS tests mirror timing and parsing cases.

Physical-device visual comparison, native iOS annotation anchoring at multiple
pixel densities, two-device transport timing and frame/CPU captures at a dense
roster remain necessary. Unit tests and source compilation cannot establish
that 60 Hz annotation/trail updates meet frame deadlines. No device-performance
or network-delay measurements are claimed by this change.

The publication cost assumptions are recorded separately in
[the three-second cadence assessment](../reviews/live-cadence-3s-cost-2026-11-17.md).
