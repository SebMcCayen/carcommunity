# Trafikverket road cameras

Android and iOS expose two independent, device-local Map layers choices:
fixed speed cameras, and a combined traffic and road condition cameras layer. Both
default off. On upgrade, either enabled legacy photo-camera choice enables the
combined layer; toggling it persists both legacy choices together. The source
camera types remain distinct in markers and details. Individual icons reuse the existing incident marker badge/ring/palette; distinct
camera glyphs and accessible labels identify each category. No camera or photo
permission is needed to view public road images.

## Metadata and cache

`roadCameras-sync` fetches `TrafficSafetyCamera` 1.0 and `Camera` 1.1 from the
`Road.Infrastructure` namespace at 04:15 Europe/Stockholm daily, using the existing
`TRAFIKVERKET_API_KEY` secret. It does not fetch photographs. Both national
responses must be structurally complete and nonempty, below the query cap, with
usable records. A greater-than-50% reduction in any established category preserves
the previous snapshot. Private records use stable hashed IDs and changed-only
writes. The public catalog is a bounded atomic snapshot of at most 40 pages of
250 entries; the previous generation remains available through the next refresh.

`roadCameras-catalog` returns metadata only, with neither image URLs nor API keys.
The device keeps a 24-hour disk cache and uses its previous cached catalog if an
update fails. It only requests the catalog when at least one camera layer is on.
Map movements filter cached markers locally. Backend access and synchronization
respect the existing `externalDataSources` flag. Metadata is public road data,
and offline copies may remain visible while a backend source is unavailable.

## Camera locations and directions

Android and iOS show one marker for photo-camera views of the same type whose
trimmed, case-insensitive names match and whose coordinates are within 50 metres
of a stable site anchor. Two fixed speed cameras with the same normalized name
form one site only within 150 metres and with valid opposing bearings (at least
135 degrees apart). Same-facing or unknown bearings, different names/types, and
distant sites remain separate. Stable ID anchors prevent proximity chains from joining
neighboring sites. Views sort clockwise from north, then by ID, with unknown
bearings last, so catalog order does not change the initial view across devices.

Below zoom 17, nearby sites of the same source type form temporary visual groups.
The radius corresponds to about 64 logical map pixels at integer zoom levels,
bounded to 10 km. Zooming in restores individual sites; a verified speed-camera
pair stays one site with two selectable directions. A white capsule retains the
source glyph and shows the source-camera count; opposing arrows are exclusive to
verified pairs. Groups are anchored at an actual camera rather than an averaged
coordinate between roads. Camera types never mix, even under the combined photo
layer toggle. Both clients group the local catalog within 50 km before the
250-marker draw cap so group counts include all candidates. Grid-indexed anchor
lookup avoids scanning every visible site against every other site on camera updates.
No map movement or zoom requests metadata or photographs.

The detail sheet shows direction buttons for sites/groups with multiple views.
Different site names are included in view labels. The main map also offers
"Show cameras" to zoom into a visual group. Android turn-by-turn uses the same
zoom grouping and view selection on its separate map.
The selected button is marked, and switching loads only that view's photograph
through the existing per-camera cache and quotas. Unknown directions use numbered
view labels; matching compass labels receive a number to keep every button distinct.
No other direction is prefetched, and a late image from a previous direction cannot
replace the current image. Grouping uses the existing catalog and requires no
backend deployment or contract/cache migration.

## Live route and convoy spacing

On Android the private approximately 1 km live tail uses the recorder's accepted
GPS fixes, the same geometry uploaded for History, instead of independently
sampling Mapbox's animated puck. Recorder ownership includes its empty initial
state; puck fixes cannot append shortcuts or omit a recorded corner under the
fallback jitter threshold. Only the newest 3,000 recorder fixes are copied for
map updates, then trimmed by distance. Puck-based jitter/jump handling remains a
fallback when no recorder is available. Nothing is road-snapped or reconstructed
across missing fixes. iOS does not currently draw this private self-tail, so the
existing Android-only rendering discrepancy does not apply to its map.

On both platforms, the vertical gap from convoy reactions to the live metrics
equals the gap from the live metrics to the convoy status bar (4 dp/pt).

## On-demand images and cost controls

A selected traffic/weather camera opens its detail view and requests
`roadCameras-photo` by opaque camera ID. Fixed speed cameras have no public
photograph. No image download is triggered by showing map markers, panning,
prefetching, or leaving a camera selected in the background. There is no automatic
image refresh. Devices cache up to 20 photos / 4 MiB for 5 minutes; repeated opens
within that time reuse the local image. HTTP Last-Modified is shown as image
update time when available; the daily catalog PhotoTime is never presented as the
timestamp of a freshly fetched image.

On Android, tapping a loaded camera image opens a full-screen view with the
camera name and Trafikverket attribution. The close button or system Back returns
to the camera details. Enlarging the image reuses the loaded bitmap and does not
request another photo; selecting another camera closes the previous enlarged view.

The image callable deliberately relays a small image so cost limits remain
server-authoritative; raw source URLs are not returned. This adds a small amount
of our own outbound traffic compared with unrestricted direct image loading.

| Operation | Per active authenticated user  | Shared budget                                                          |
| --------- | ------------------------------ | ---------------------------------------------------------------------- |
| Photo     | 6/minute, 30/hour, 100/UTC day | 128 MiB/UTC day, reserving the maximum encoded response before any I/O |
| Catalog   | 3/hour, 10/UTC day             | 100 responses/UTC day, each at most 2 MiB                              |

Reservations are atomic Firestore transactions spanning the user's counters and
global counters. Cache hits at the server and failed attempts still consume the
reservation. Rejected requests have no upstream download or image payload, but
Cloud Functions invocations and authorization/counter reads can still cost money;
these controls are not an absolute cap on the entire cloud bill. App Check and
active-account checks apply to both callables. Native clients also throttle
repeated remote requests and surface a localized limit message.

Each decoded image is at most 128 KiB. Only the documented HTTPS Trafikverket image
host/path is allowed, redirects are refused, streams are bounded even without
Content-Length, and JPEG/PNG content types and signatures are checked. Per-instance
image caching is bounded to 20 entries; durable server image storage is not added.
No raw images, source URLs, API keys, or member locations are logged. All backend
camera records, snapshot pages, metadata, and quota counters are client-denied by
the existing Firestore deny-all rule. Counter documents are fixed per UID plus one
global document, so no per-request or per-window ledger accumulates. A two-day expiresAt TTL policy removes inactive counters; expiration is never used to authorize a request. Deploy the accompanying field overrides with the backend release.

## Delivery and verification

Deploy the three `roadCameras` functions using the normal reviewed backend release
process, then allow the first daily metadata sync to complete before enabling the
layers for testing. The normal Android/iOS release process is required to ship
the native UI. This change does not itself deploy or release applications.

Acceptance scenarios on both platforms: independent layer toggles persist across
restart; disabled layers cause no camera download; map markers use distinct icons;
opening a photo camera loads one image; reopening within5 minutes is cached; speed
camera selection never loads a photo; switching cameras cannot display the earlier
camera's image; server quota errors show localized text; stale cached metadata
survives a transient source failure. Backend tests cover schema mapping, partial
source preservation, image SSRF/size/type protection, and quota boundaries.

Sources: [Trafikverket models](https://data.trafikverket.se/documentation/datacache/data-model),
[API license](https://data.trafikverket.se/oauth2/Account/register).
