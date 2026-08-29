// The seam between the map-first shell and the actual map renderer, kept free
// of any map SDK so the shell compiles, unit-tests and passes CI without a
// device, GPS or a Mapbox access token.
//
// This is the iOS port of the Android app's `shell/MapSurface.kt` — the same
// contract, expressed natively. The two files are intentionally parallel: when
// one platform changes the seam, port the change (and its tests) to the other,
// per the mobile platform parity instructions. Observable state is exposed via
// the Observation framework (`@Observable` on the implementations) instead of
// Android's `StateFlow`s; the protocol requirements below are the read-only
// views of that state.
//
// Deliberate deviations from the Kotlin source (documented per the parity
// instructions):
// - No `Content` view requirement yet. Android's `MapSurface.Content` is the
//   composable that renders the surface; the iOS map view (and with it the
//   stub's placeholder rendering, its composition counter and its pan-gesture
//   counter) arrives with the map-UI PR. The seam stays UI-framework-free so
//   this file is pure Swift.
// - Android's `@DrawableRes iconRes: Int` marker fields are `iconName: String`
//   here (an asset-catalog / SF Symbol name) — the platform-native way to hand
//   the surface a glyph without coupling the seam to UIKit types.
// - Packed ARGB colours cross the seam as `UInt32` rather than Kotlin's signed
//   `Int` — same 0xAARRGGBB layout, no sign games.

import Foundation
import Observation

/// Load state of the map render, driving the transient "Loading roads…"
/// status line in the shell. Mirrors a typical tile/style load lifecycle.
enum MapLoadState: Sendable {
    /// Style/tiles are being fetched — the shell shows "Loading roads…".
    case loading

    /// The map is interactive — the status line is hidden.
    case loaded
}

/// Which light preset the Mapbox Standard style renders: ``day`` (the default,
/// bright basemap) or ``night`` (the dark/dusk preset). Toggled by the
/// map-layers popup's day/night switch. A surface that cannot re-style (the
/// stub) still exposes the flag so the shell wiring stays uniform; only the
/// real Mapbox surface actually swaps the style's `lightPreset`.
enum MapMode: Sendable {
    /// Bright/day light preset (default).
    case day

    /// Dark/night light preset.
    case night
}

/// How the map is ORIENTED while it follows the user — toggled by the
/// floating compass control.
///
/// - ``courseUp`` (default): the camera rotates so the user's travel direction
///   (the puck's COURSE bearing) points up — a "follow your direction" driving
///   view. Position following is unchanged; only the bearing tracks the
///   heading. This is the default a user gets on first run (they can switch to
///   ``northUp`` via the compass control, and that choice then persists).
/// - ``northUp``: the camera bearing stays at 0 so true north is always at the
///   top of the screen. Following the user's POSITION continues; only the
///   rotation is pinned.
///
/// This is a first-class part of the ``MapSurface`` seam (like ``MapMode``) so
/// the shell can drive it and the real surface can feed the chosen bearing
/// into its SINGLE follow path rather than spinning up a second, competing
/// camera loop.
///
/// The raw values are the exact names Android persists (`"NorthUp"` /
/// `"CourseUp"`), so a stored preference means the same thing on both
/// platforms and the defensive parse below stays byte-for-byte compatible.
enum MapCompassMode: String, CaseIterable, Sendable {
    /// North stays at the top; the camera follows position only.
    case northUp = "NorthUp"

    /// The map rotates to keep the user's heading pointing up (course-up,
    /// default).
    case courseUp = "CourseUp"

    /// The OTHER mode — the pure toggle the compass control applies on each
    /// tap (north-up ⇄ course-up). Extracted so the "a tap flips to the
    /// opposite mode" contract is unit-testable without building the button.
    func toggled() -> MapCompassMode {
        switch self {
        case .northUp: .courseUp
        case .courseUp: .northUp
        }
    }

    /// The default orientation when the user has never chosen one (first run /
    /// unset preference): COURSE-UP, so the map rotates with the direction of
    /// travel out of the box. A stored choice overrides this — see
    /// ``fromStoredName(_:)``.
    static let defaultMode: MapCompassMode = .courseUp

    /// Parses a persisted mode name. Unknown / absent / corrupt names fall
    /// back to ``defaultMode`` rather than trapping: this is read during map
    /// start-up, so a failure here would be a launch crash after an enum
    /// rename or a hand-edited preference. (Same defensive parse as Android's
    /// `MapCompassMode.fromStoredName`.)
    static func fromStoredName(_ name: String?) -> MapCompassMode {
        guard let name else { return defaultMode }
        return MapCompassMode(rawValue: name) ?? defaultMode
    }
}

/// The caller's own marker state. ``label`` is the display name;
/// ``isLiveSharing`` is true while the user is actively live-sharing their
/// location (drives the green puck pulse), false otherwise.
struct MapUserMarker: Equatable, Sendable {
    let label: String
    let isLiveSharing: Bool
}

/// A single lng/lat vertex of a drawn route line.
struct MapPoint: Equatable, Sendable {
    let longitude: Double
    let latitude: Double
}

/// A destination + the route line to draw for it. Owned by the shell (kept
/// free of the navigation feature's types) so the ``MapSurface`` seam stays
/// self-contained; the host maps a resolved route onto this. An empty ``path``
/// still marks the destination (line simply not drawn).
///
/// `bottomInsetPx` is the device pixels along the BOTTOM edge the camera fit
/// must leave clear, because the host is covering that much of the map with
/// its own chrome — in practice the route-preview sheet at its collapsed
/// height. Nil (the default) keeps the surface's own built-in bottom padding,
/// so a caller that draws nothing over the map does not have to say so.
/// Reported by the host rather than assumed here: only the host knows how
/// tall its sheet ended up at the current Dynamic Type size and safe-area
/// inset, and a fixed guess is what previously framed routes into the top
/// half of the screen.
struct MapRouteOverlay: Equatable, Sendable {
    let destination: MapPoint
    let path: [MapPoint]
    let bottomInsetPx: Double?

    init(destination: MapPoint, path: [MapPoint], bottomInsetPx: Double? = nil) {
        self.destination = destination
        self.path = path
        self.bottomInsetPx = bottomInsetPx
    }
}

/// A crowd-sourced incident marker to draw on the map (the Waze-style layer,
/// shared by all users). Shell-owned and self-contained so the ``MapSurface``
/// seam stays free of the incidents feature's types: the host resolves the
/// category into the primitives below, and ``id`` identifies the marker both
/// for de-duplication and for reporting a tap back
/// (``MapSurface/emitIncidentTap(_:)``).
///
/// The marker is an ICON on a coloured disc, not a bare coloured dot — colour
/// alone could not tell one category from another for a colour-blind user, so
/// the glyph carries the meaning.
///
/// - `colorArgb`: the category disc colour (packed 0xAARRGGBB), resolved by
///   the host.
/// - `iconName`: image name for the category glyph. A plain name (a `String`)
///   rather than a category type, which is what keeps this seam free of the
///   incidents feature while still carrying the shape.
/// - `glyphColorArgb`: the colour to tint `iconName` with — chosen by the host
///   per category for contrast against `colorArgb`, since a single fixed
///   glyph colour is unreadable on some discs.
/// - `reportedCleared`: whether members have voted this incident GONE without
///   yet reaching the backend's removal threshold. The marker is still drawn
///   — one member's vote must not erase a real hazard for everyone — but
///   struck through with a diagonal bar, a SHAPE difference that survives any
///   colour-vision deficiency. The host has already washed out `colorArgb`
///   and re-picked `glyphColorArgb` to match, so this flag only selects the
///   extra mark.
struct MapIncidentMarker: Equatable, Sendable {
    let id: String
    let longitude: Double
    let latitude: Double
    let colorArgb: UInt32
    let iconName: String
    let glyphColorArgb: UInt32
    let reportedCleared: Bool

    init(
        id: String,
        longitude: Double,
        latitude: Double,
        colorArgb: UInt32,
        iconName: String,
        glyphColorArgb: UInt32,
        reportedCleared: Bool = false
    ) {
        self.id = id
        self.longitude = longitude
        self.latitude = latitude
        self.colorArgb = colorArgb
        self.iconName = iconName
        self.glyphColorArgb = glyphColorArgb
        self.reportedCleared = reportedCleared
    }
}

/// A community EVENT pin to draw on the map (visible to every signed-in
/// user). Shell-owned and self-contained so the ``MapSurface`` seam stays free
/// of the events feature's types: the host resolves a published, upcoming
/// event into this, and ``id`` identifies the pin both for de-duplication and
/// for reporting a tap back (``MapSurface/emitEventTap(_:)``) so the host can
/// open the event.
///
/// Unlike ``MapIncidentMarker`` there is no per-category colour/glyph — every
/// event is one distinct event badge — so this carries only the id and
/// position; the one event icon/colour is a fixed part of the surface's event
/// layer.
struct MapEventMarker: Equatable, Sendable {
    let id: String
    let longitude: Double
    let latitude: Double
}

/// One sponsored BILLBOARD to draw on the map.
///
/// The same shape as ``MapEventMarker`` and for the same reason: there is
/// exactly one billboard marker image (a billboard has no per-item variants
/// the way an incident has categories or a crown has rarities), so the
/// marker's appearance is a fixed part of the surface's billboard layer and
/// only the id and position cross the seam. The host resolves a tapped ``id``
/// back to the billboard it holds, and opens its popup.
///
/// Its OWN type rather than a reused ``MapEventMarker`` for the reason spelled
/// out on ``MapCrownMarker``: the two are drawn by different managers into
/// different style images, and a shared type would turn "which layer is
/// this?" from a compile-time question into a runtime one — with a tap that
/// opened an event popup for an advert as the payoff.
struct MapBillboardMarker: Equatable, Sendable {
    let id: String
    let longitude: Double
    let latitude: Double
}

/// One auto-spawned Kronjakt crown to draw on the map.
///
/// The exact sibling of ``MapIncidentMarker``, and separate from it for the
/// same reason that one is separate from the incidents feature's types: the
/// surface is handed PRIMITIVES (a colour, a glyph name, an id) and knows
/// nothing about rarities, point values or collect radii. The host resolves a
/// crown spawn into this, and resolves a tapped ``id`` back again.
///
/// Why not reuse ``MapIncidentMarker`` with a nullable glow: the two layers
/// are drawn by different managers, into different style images, and a shared
/// type would make "which layer does this marker belong to?" a runtime
/// question. It is currently a compile-time one, and a tap that opened an
/// incident sheet for a crown would be exactly the bug that costs.
///
/// - `discColorArgb`: the rarity disc colour, resolved by the host.
/// - `iconName`: image name for the rarity's crown silhouette. Silhouette is
///   the primary rarity channel (it survives any colour-vision deficiency and
///   any basemap); the disc colour reinforces it.
/// - `glyphColorArgb`: the tint for `iconName`, chosen by the host for
///   contrast against `discColorArgb`.
/// - `glowColorArgb`: a soft halo drawn OUTSIDE the rings, or nil for the
///   tiers that have none (only the legendary tier glows).
/// - `collectedByYou`: whether to stamp the distinct "you already collected
///   this" check badge on the marker — true only for a SHARED crown the
///   current member has picked up but which stays live on the map for others.
///   Baked into the marker image (not a separate layer), so it scales at
///   every zoom.
struct MapCrownMarker: Equatable, Sendable {
    let id: String
    let longitude: Double
    let latitude: Double
    let discColorArgb: UInt32
    let iconName: String
    let glyphColorArgb: UInt32
    let glowColorArgb: UInt32?
    let collectedByYou: Bool

    init(
        id: String,
        longitude: Double,
        latitude: Double,
        discColorArgb: UInt32,
        iconName: String,
        glyphColorArgb: UInt32,
        glowColorArgb: UInt32?,
        collectedByYou: Bool = false
    ) {
        self.id = id
        self.longitude = longitude
        self.latitude = latitude
        self.discColorArgb = discColorArgb
        self.iconName = iconName
        self.glyphColorArgb = glyphColorArgb
        self.glowColorArgb = glowColorArgb
        self.collectedByYou = collectedByYou
    }
}

/// A point in the map view's own pixel space, as the renderer projected it.
/// Origin is the view's top-left, y grows downward.
///
/// `trustworthy` is the renderer's own verdict on whether this pixel is an
/// HONEST projection of the coordinate or a fold/clamp of a point with no real
/// screen position (behind a tilted camera / beyond the horizon / off the
/// projectable globe). On a pitched map the SDK's point-for-coordinate does
/// not fail for a behind-camera point — it returns a folded pixel (sometimes
/// clamped to the origin corner), and a bare bounds test then pins a chip
/// there ("off-screen live user stuck in the top-left corner"). A false
/// `trustworthy` means "this (x, y) is a fold — do not place anything at it".
/// Defaults true so a renderer that cannot self-assess (the stub, older
/// callers) is taken at face value.
struct MapScreenPoint: Equatable, Sendable {
    let x: Double
    let y: Double
    let trustworthy: Bool

    init(x: Double, y: Double, trustworthy: Bool = true) {
        self.x = x
        self.y = y
        self.trustworthy = trustworthy
    }
}

/// Where a map is looking, expressed the way the nearby-query callables want
/// it: a centre plus the radius that covers what is on screen.
///
/// It exists so a map that is NOT the shell surface can anchor the shared
/// `incidents.listNearby` poll. While turn-by-turn navigation is running the
/// shell map is stood down (``MapSurface/setActive(_:)``) and its camera stops
/// moving, so the poll would go on asking about the point the trip started
/// from for the whole drive. The navigation screen reports one of these
/// instead, and the host prefers it while it is non-nil. (Turn-by-turn is not
/// in iOS v1 — see ADR-002 — but the type is ported whole so the two
/// platforms share one seam.)
///
/// Deliberately NOT a second poll and NOT a second cadence: it only changes
/// WHERE the existing poll looks.
struct MapQueryViewport: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let radiusMeters: Double
}

/// A settled snapshot of where the camera is.
///
/// Deliberately ROUNDED (see ``of(latitude:longitude:zoom:bearing:pitch:)``)
/// rather than raw. The map emits a camera change on every animation frame; a
/// snapshot carrying full precision would therefore be a new value ~60 times
/// a second and re-run every consumer with it. Rounding to about a metre, a
/// hundredth of a zoom level and a whole degree makes consecutive frames of a
/// settled camera compare EQUAL, so equality-gated observers collapse them
/// and only real camera movement propagates.
struct MapCameraSnapshot: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let zoom: Double
    let bearing: Double
    let pitch: Double

    /// Rounds a raw camera state into a de-duplicable snapshot.
    static func of(
        latitude: Double,
        longitude: Double,
        zoom: Double,
        bearing: Double,
        pitch: Double
    ) -> MapCameraSnapshot {
        MapCameraSnapshot(
            // 5 decimal places of latitude is a bit over a metre — finer than
            // the camera movement anyone can see, coarser than float noise.
            latitude: round(latitude, decimals: coordinateDecimals),
            longitude: round(longitude, decimals: coordinateDecimals),
            zoom: round(zoom, decimals: zoomDecimals),
            bearing: round(bearing, decimals: 0),
            pitch: round(pitch, decimals: 0)
        )
    }

    private static let coordinateDecimals = 5
    private static let zoomDecimals = 2

    private static func round(_ value: Double, decimals: Int) -> Double {
        guard value.isFinite else { return 0.0 }
        var factor = 1.0
        for _ in 0..<decimals { factor *= 10.0 }
        // Ties round to even, the same rule as Android's `kotlin.math.round`,
        // so both platforms snapshot an identical camera to identical values.
        return (value * factor).rounded(.toNearestOrEven) / factor
    }
}

/// A user gesture on the map asking "navigate to this place?".
///
/// Raised by BOTH map gestures that mean the same thing, so they resolve to
/// the SAME confirmation instead of two parallel ones:
/// - a long-press on open map — ``name`` is nil (nothing is there to name, so
///   the host reverse-geocodes / falls back to a "dropped pin" label);
/// - a single tap on a place the basemap already draws (a shop, a petrol
///   station, a restaurant) — ``name`` is that place's own label, so the
///   confirmation can say where the user is actually going.
///
/// The distinction is deliberately only the ``name``: everything downstream
/// (the preview, the route, the confirmation) treats the two identically.
struct MapPlaceRequest: Equatable, Sendable {
    let point: MapPoint
    let name: String?
}

/// The narrow "where does this coordinate land on screen?" half of a map.
///
/// Split out of ``MapSurface`` so marker overlays (the convoy awareness layer
/// and the nearby-live-sharer layer, when they are ported) can be drawn over
/// ANY map, not just the shell's. They need exactly two things: the settled
/// camera (to know when to reproject) and the renderer's own projection. They
/// do not need traffic toggles, incident markers, place gestures or a
/// day/night preset, and demanding the whole of ``MapSurface`` is precisely
/// what would confine them to the shell map. (On Android the turn-by-turn
/// screen's Navigation-SDK map implements only this half, which is what lets
/// the same overlays draw there too.)
@MainActor
protocol MapProjection: AnyObject {
    /// Where the camera currently is, or nil before the map has one.
    ///
    /// Rounded and de-duplicated by the implementation (see
    /// ``MapCameraSnapshot``), so a settled camera does not re-run every
    /// consumer on every frame. A map with no real camera (the stub) leaves
    /// this nil forever, and the overlays then draw nothing.
    var cameraSnapshot: MapCameraSnapshot? { get }

    /// Project a geographic coordinate into the map view's pixel space, or
    /// nil when there is no map to project with (not on screen, no style, or
    /// the stub), or the projection is non-finite.
    ///
    /// This is the renderer's OWN projection, deliberately, because it is the
    /// only thing that accounts exactly for zoom, rotation AND pitch.
    /// Reimplementing it would mean reimplementing the camera's projection
    /// matrix.
    ///
    /// A non-nil result carries ``MapScreenPoint/trustworthy``. The pixel may
    /// be far OUTSIDE the viewport (a coordinate genuinely off to one side —
    /// the caller decides on/off-screen with the viewport rectangle), and it
    /// may be UNTRUSTWORTHY: on a pitched map a coordinate behind the camera
    /// / beyond the horizon has no honest screen position, and the SDK's
    /// projection does not fail — it FOLDS or CLAMPS the point back into view.
    /// The implementation detects that with a coordinate round trip and
    /// returns the folded pixel with `trustworthy = false`, so a caller can
    /// BOTH refuse to place a marker there AND log the raw pixel.
    func screenPositionFor(latitude: Double, longitude: Double) -> MapScreenPoint?
}

/// Seam between the map-first shell and the actual map renderer.
///
/// The entire shell (search bar, "Loading roads…" state, floating controls,
/// "Create route" CTA, bottom nav) is written against this abstraction so it
/// compiles, unit-tests, and passes CI **without** a device, GPS, or a Mapbox
/// access token. The current implementation is ``StubMapSurface``; the real
/// Mapbox render + live GPS drop in later behind this same protocol (the
/// Mapbox SDK is deliberately NOT a dependency of this seam).
///
/// Hooks (mirroring Android's `MapSurface`):
/// - ``recenter()`` — recentre the camera on the user (stub records the
///   request).
/// - ``setUserMarker(_:)`` — supply/clear the caller's live-sharing marker
///   state (``MapUserMarker``: a label plus `isLiveSharing`, which drives the
///   puck's green/blue pulse).
/// - ``loadState`` — drives the shell's "Loading roads…" indicator.
/// - ``trafficEnabled`` / ``setTrafficEnabled(_:)`` — the optional
///   traffic-congestion overlay the layers control toggles. A surface that
///   cannot draw traffic (the stub) still exposes the flag so the shell
///   wiring stays uniform.
/// - ``mapMode`` / ``setMapMode(_:)`` — day vs night light preset of the
///   Standard style, toggled by the layers popup (stub exposes the flag
///   only).
/// - ``is3D`` / ``set3DEnabled(_:)`` — tilted 3D vs flat top-down 2D camera,
///   toggled by the layers popup; the real surface flips the camera pitch and
///   re-centres (stub exposes the flag only).
@MainActor
protocol MapSurface: MapProjection {
    /// Current load state; the shell shows "Loading roads…" while
    /// ``MapLoadState/loading``.
    var loadState: MapLoadState { get }

    /// The user marker the surface should draw, or nil when none is set.
    var userMarker: MapUserMarker? { get }

    /// Whether the traffic-congestion overlay is currently shown.
    var trafficEnabled: Bool { get }

    /// The current light preset (day/night) of the Standard style.
    var mapMode: MapMode { get }

    /// Whether the camera is in tilted 3D mode (true) or flat 2D (false).
    var is3D: Bool { get }

    /// The current map bearing in degrees (0 = north-up, clockwise). Observed
    /// by the floating compass control, which rotates its north-arrow by the
    /// negative of this value so the arrow keeps pointing to true north as
    /// the map rotates. A surface that cannot rotate (the stub) exposes a
    /// constant 0.
    var bearing: Double { get }

    /// The destination + route line to draw, or nil when none is set.
    var routeOverlay: MapRouteOverlay? { get }

    /// The crowd-sourced incident markers to draw (the shared incidents
    /// layer).
    var incidentMarkers: [MapIncidentMarker] { get }

    /// The community event pins to draw (the shared events layer, visible to
    /// every signed-in user). A separate layer from the incidents one: an
    /// event is a different thing from a road hazard, with its own icon, its
    /// own tap intent ("open this event") and its own ``eventTap`` slot.
    var eventMarkers: [MapEventMarker] { get }

    /// The Kronjakt crowns to draw (the auto-spawn layer).
    ///
    /// Empty whenever the `crownHuntSpawn` flag is off — the host does not
    /// even query in that case, so "off" costs nothing rather than costing a
    /// hidden layer's worth of reads.
    var crownMarkers: [MapCrownMarker] { get }

    /// The sponsored billboards to draw (the billboards layer).
    ///
    /// Empty whenever the `digitalBillboards` flag is off, and empty whenever
    /// the server says a billboard is not currently map-visible. There is no
    /// member-facing toggle for this layer: the map is the ONLY place a
    /// billboard appears, and an admin deciding it is active is the only
    /// thing that puts it there.
    var billboardMarkers: [MapBillboardMarker] { get }

    // `cameraSnapshot` and `screenPositionFor` are inherited from
    // `MapProjection` — the two members the marker overlays actually need.
    // They live there, not here, so those overlays can also be drawn over a
    // future map that is not a MapSurface.

    /// The radius in METRES that covers the currently-visible map, for the
    /// incident layer to query around the camera centre — or nil when there
    /// is no live camera to measure (the stub reports a fixed default instead
    /// of nil so the config-less build still queries a sane area).
    ///
    /// The real surface derives this from the camera's visible bounds, so a
    /// street-level view queries a small precise radius and a regional view
    /// grows it to fill the screen — clamped to the server's [100 m, 50 km].
    /// Kept on the seam because the only honest read of what is on screen is
    /// the renderer's own camera.
    func visibleRadiusMeters() -> Double?

    /// Frame `points` (with padding) instead of following the user, or pass
    /// nil to go back to normal follow.
    ///
    /// This is how "keep the whole convoy in view" is expressed, and it is
    /// routed through the surface's EXISTING follow path rather than moving
    /// the camera itself: the same manual-gesture detach, the same
    /// idle-return timer, and the same deference to a route overlay all apply
    /// unchanged. Two camera owners fighting each other is the failure mode
    /// of this feature, so there is exactly one.
    ///
    /// Clearing it (nil) restores the normal framing, including the zoom, so
    /// a convoy that ends cannot leave the camera stuck zoomed out over the
    /// group. A no-op beyond storing the value on the stub.
    ///
    /// `focusEnabled` is the USER'S CHOICE — whether convoy focus is switched
    /// on — and is deliberately separate from whether `points` happens to be
    /// nil. The two differ, and conflating them is a real bug: the planner
    /// also returns nil when focus IS on but there is nothing fittable yet
    /// (nobody sharing a position, or only one point). Inferring "the user
    /// toggled focus off" from a nil would then fire on a transient data gap
    /// and force-resume following, yanking the camera back from a user who
    /// had deliberately panned away. Only a change in `focusEnabled` counts
    /// as the deliberate act.
    func setConvoyFit(points: [MapPoint]?, focusEnabled: Bool)

    /// Glide the camera ONCE to `point`, as a one-shot "show me this spot" —
    /// the convoy member-list's "Go to location" uses it to centre on the
    /// tapped member's live marker.
    ///
    /// Deliberately NOT part of the convoy-fit / focus-mode machinery: it is
    /// a single ease modelled on a manual pan, so it goes through the SAME
    /// follow detach + idle-return path a real pan does. That keeps the
    /// single camera-owner invariant ``setConvoyFit(points:focusEnabled:)``
    /// documents: this never becomes a second owner fighting the follow path,
    /// it briefly borrows it. A no-op on the stub beyond recording the
    /// request for tests.
    func centerOn(_ point: MapPoint)

    /// The most recent "navigate to this place?" gesture, or nil when none is
    /// pending. The host observes this to open the navigation preview for the
    /// requested place, then calls ``consumePlaceRequest()`` to clear it.
    ///
    /// ONE slot for both gestures (``emitLongPress(_:)`` and
    /// ``emitPlaceTap(_:name:)``) so a long-press and a place tap can never
    /// drift into two different confirmations — see ``MapPlaceRequest``.
    var placeRequest: MapPlaceRequest? { get }

    /// The id of the incident marker most recently TAPPED, or nil when none
    /// is pending. The host observes this to open the incident detail sheet,
    /// then calls ``consumeIncidentTap()`` to clear it.
    ///
    /// Deliberately a THIRD, separate slot rather than another producer of
    /// ``placeRequest``: a tap on an incident marker means "tell me about
    /// this incident", which is a different intent from the two gestures that
    /// mean "navigate to this place" — routing it through ``placeRequest``
    /// would open a route preview to the crash you were asking about.
    ///
    /// Only the id crosses the seam. The surface has no idea what an incident
    /// IS (it was handed a colour, a glyph and an id to draw), so the host
    /// resolves the id back to the incident it already holds.
    var incidentTap: String? { get }

    /// The id of the event pin most recently TAPPED, or nil when none is
    /// pending. The host observes this to open the event info popup, then
    /// calls ``consumeEventTap()`` to clear it.
    ///
    /// A separate slot from ``incidentTap`` for the same reason that one is
    /// separate from ``placeRequest``: tapping an event pin means "tell me
    /// about this event", a different intent from an incident tap or a
    /// "navigate here" gesture. Only the id crosses the seam.
    var eventTap: String? { get }

    /// The id of the crown marker most recently TAPPED, or nil when none is
    /// pending. The host observes this to open the crown popup, then calls
    /// ``consumeCrownTap()``.
    ///
    /// A SEPARATE slot rather than a second producer of ``incidentTap``, for
    /// the same reason ``incidentTap`` is not a producer of ``placeRequest``:
    /// the two ids come from different collections and mean different things,
    /// and one shared slot would let a crown id be looked up among the
    /// incidents (finding nothing, so the tap would silently do nothing) — a
    /// failure with no symptom.
    var crownTap: String? { get }

    /// The id of the billboard marker most recently TAPPED, or nil when none
    /// is pending. The host observes this to open the billboard popup, then
    /// calls ``consumeBillboardTap()``.
    ///
    /// A SEPARATE slot, for the reason given on ``crownTap``: four layers
    /// draw onto this surface, and a shared tap slot would let an id from one
    /// collection be looked up in another — finding nothing, so the tap would
    /// silently do nothing. A failure with no symptom is the one to design
    /// out.
    var billboardTap: String? { get }

    /// Recentre the camera on the user's position.
    func recenter()

    /// Record a long-press on open map at `point` (no place name available).
    /// Called by the real surface's long-press gesture handler; also drivable
    /// by the stub/tests to simulate the gesture.
    func emitLongPress(_ point: MapPoint)

    /// Record a single tap on a basemap place at `point`, named `name`.
    /// Called by the real surface's place-tap interaction; also drivable by
    /// the stub/tests.
    func emitPlaceTap(_ point: MapPoint, name: String?)

    /// Clear the pending ``placeRequest`` once the host has opened the
    /// preview for it.
    func consumePlaceRequest()

    /// Record a tap on the incident marker with `incidentId`. Called by the
    /// real surface's annotation tap handler; also drivable by the stub/tests
    /// to simulate the tap without a GL surface.
    func emitIncidentTap(_ incidentId: String)

    /// Clear the pending ``incidentTap`` once the host has opened the sheet
    /// for it.
    func consumeIncidentTap()

    /// Record a tap on the event pin with `eventId`. Called by the real
    /// surface's annotation tap handler; also drivable by the stub/tests.
    func emitEventTap(_ eventId: String)

    /// Clear the pending ``eventTap`` once the host has opened the popup for
    /// it.
    func consumeEventTap()

    /// Record a tap on the crown marker with `spawnId`. Called by the real
    /// surface's annotation tap handler; also drivable by the stub/tests.
    func emitCrownTap(_ spawnId: String)

    /// Clear the pending ``crownTap`` once the host has opened the popup for
    /// it.
    func consumeCrownTap()

    /// Record a tap on the billboard marker with `billboardId`. Called by the
    /// real surface's annotation tap handler; also drivable by the
    /// stub/tests.
    func emitBillboardTap(_ billboardId: String)

    /// Clear the pending ``billboardTap`` once the host has opened the popup
    /// for it.
    func consumeBillboardTap()

    /// Reset the map to north-up: ease the camera bearing back to 0. Moves no
    /// camera on the stub, which has no rotatable one — it only records the
    /// call in ``StubMapSurface/resetNorthCount``.
    func resetNorth()

    /// Re-centre on the user AND reset the bearing to north-up, as ONE camera
    /// move — what the floating compass control does.
    ///
    /// Deliberately a single method rather than a ``resetNorth()`` +
    /// ``recenter()`` pair at the call site: those are two independent eased
    /// camera animations on the same camera, and the second one issued
    /// cancels the first mid-flight, so a caller firing both gets a visible
    /// stutter and, depending on ordering, a bearing or a centre that never
    /// arrives. Implementations MUST express this as one camera update.
    ///
    /// Recentring is best-effort in the same way ``recenter()`` is — with no
    /// location fix (or no location permission) there is nothing to centre
    /// ON, so the implementation falls back to the same default camera
    /// ``recenter()`` uses. The north-up reset is NOT best-effort: it applies
    /// either way.
    func recenterNorthUp()

    /// Choose how the map is ORIENTED while following the user (see
    /// ``MapCompassMode``) — the compass control's two modes.
    ///
    /// Stores the chosen mode so the surface's EXISTING follow path applies
    /// the right bearing on every position/heading update: north-up pins the
    /// bearing at 0, course-up rotates the camera to the puck's heading.
    /// Deliberately fed into the one follow controller rather than a second
    /// camera owner (two camera loops fighting is the documented failure mode
    /// here).
    ///
    /// When the mode actually CHANGES, the surface applies it immediately as
    /// ONE user-requested re-centre — resuming follow, cancelling any pending
    /// idle-return, and easing to the user with the new orientation.
    /// Re-setting the SAME mode is a no-op, so the shell can re-push it on a
    /// surface swap without forcing a spurious camera move on open. On the
    /// stub this only records the mode (and mirrors the re-centre in
    /// ``StubMapSurface/recenterCount``).
    func setCompassMode(_ mode: MapCompassMode)

    /// Re-apply the device-location component after the runtime
    /// location-permission grant, so the blue puck appears without recreating
    /// the map (the component is enabled at style-load, before the grant). A
    /// no-op on the stub, which has no device/GPS.
    func refreshLocationComponent()

    /// Whether the map is actually visible to the user.
    ///
    /// The map stays alive for the whole signed-in shell — every bottom-nav
    /// tab, every full-screen route and the address search — so its render
    /// surface and loaded style survive every navigation. Disposing it made
    /// coming back rebuild the whole map view and blank the screen for a
    /// beat. The price of keeping it alive is that a map nobody can see would
    /// otherwise keep pulsing its puck (continuous redraw) and keep consuming
    /// location fixes, so the shell calls `setActive(false)` while something
    /// OPAQUE covers it and `setActive(true)` when it is visible again.
    ///
    /// Note "visible", not "the active page": the address-search overlay
    /// draws its chrome over a map the user is still looking at (it shows the
    /// route and the puck), so the map stays ACTIVE underneath it — see
    /// ``ShellNavigation/mapCover(tab:route:navigating:navSearchOpen:)``. Only
    /// a page that actually hides the map stands it down.
    ///
    /// Deactivating only stands the location component down; the surface, the
    /// style and the camera are all left intact, which is the whole point. A
    /// no-op on the stub, which has no device/GPS.
    func setActive(_ active: Bool)

    /// Set (or clear, with nil) the caller's own marker.
    func setUserMarker(_ marker: MapUserMarker?)

    /// Show or hide the traffic-congestion overlay (no visible effect on the
    /// stub).
    func setTrafficEnabled(_ enabled: Bool)

    /// Switch the day/night light preset (no visible effect on the stub).
    func setMapMode(_ mode: MapMode)

    /// Switch between tilted 3D (true) and flat 2D (false) (no visible effect
    /// on the stub).
    func set3DEnabled(_ enabled: Bool)

    /// Set the RESTING/browsing zoom — "how far away the focus is" when the
    /// map is used as usual. The real surface applies it as the zoom the
    /// camera opens on the user at and re-centres to while browsing, and —
    /// when neither a route preview nor a convoy fit owns the camera — eases
    /// the current camera to it so a slider drag reads live. It deliberately
    /// does NOT touch the active drive-follow framing (following a moving
    /// puck only re-centres, leaving the zoom the user is at). No visible
    /// effect on the stub, which records the value for tests.
    func setBrowsingZoom(_ zoom: Double)

    /// Draw (or clear, with nil) a destination marker + route line and fit
    /// the camera to it. A no-op beyond storing the value on the stub.
    func setRouteOverlay(_ overlay: MapRouteOverlay?)

    /// RESTORE the on-screen "road just driven" tail (the private breadcrumb)
    /// from `points` after a process death mid-drive.
    ///
    /// The recorded drive itself is persisted incrementally and resumed into
    /// the recorder on relaunch, but the breadcrumb the user actually SEES is
    /// a memory-only buffer fed purely from live position fixes — so on a
    /// cold start it begins empty and the in-progress drive looks lost until
    /// another window's worth of driving redraws it. The host calls this
    /// once, with the resumed route, so the tail reappears immediately.
    ///
    /// Idempotent and race-safe by contract: the surface applies the seed
    /// only when it is live-sharing AND the tail is still empty, so it never
    /// clobbers a tail already being rebuilt by live fixes. Passing an empty
    /// array is a no-op. A no-op beyond recording the value on the stub.
    func seedBreadcrumb(_ points: [MapPoint])

    /// Draw (or clear, with nil / empty) the SHARED convoy "Follow me" LEADER
    /// TRAIL — the line of where the current trail leader has recently
    /// driven, drawn on this member's map so a separated member can rejoin.
    /// `points` run oldest→newest (the head is the leader's latest position).
    ///
    /// A DISTINCT line from the private self-breadcrumb
    /// (``seedBreadcrumb(_:)``): different layer identity and a different
    /// colour, so a member can tell the leader's shared trail apart from
    /// their own tail. The host decides visibility and pushes nil to take the
    /// line down. A no-op beyond storing the value on the stub.
    func setFollowMeTrail(_ points: [MapPoint]?)

    /// Replace the set of incident markers drawn on the map. The host fetches
    /// these near the viewport via `incidents.listNearby` and pushes them
    /// here so every user sees them. A no-op beyond storing the value on the
    /// stub.
    func setIncidentMarkers(_ markers: [MapIncidentMarker])

    /// Replace the set of community event pins drawn on the map. The host
    /// derives these from the published, upcoming events it observes and
    /// pushes them here so every signed-in user sees them. A no-op beyond
    /// storing the value on the stub.
    func setEventMarkers(_ markers: [MapEventMarker])

    /// Replace the set of Kronjakt crowns drawn on the map. The host reads
    /// these from `crownSpawns` around the viewport and pushes them here.
    /// Pushing an empty array is how the layer is taken DOWN — which is what
    /// the host does the moment the `crownHuntSpawn` flag reads false. A
    /// no-op beyond storing the value on the stub.
    func setCrownMarkers(_ markers: [MapCrownMarker])

    /// Replace the set of sponsored billboards drawn on the map. The host
    /// reads these from the `billboards` collection and pushes them here.
    /// Pushing an empty array is how the layer is taken DOWN, which is what
    /// the host does the moment the `digitalBillboards` flag reads false. A
    /// no-op beyond storing the value on the stub.
    func setBillboardMarkers(_ markers: [MapBillboardMarker])

    // Android's seam additionally requires `Content(modifier:)` — the
    // composable that renders the surface. The iOS view requirement arrives
    // with the map-UI PR (see the file-header deviation note), keeping this
    // seam pure Swift.
}

/// Placeholder ``MapSurface`` with no device/SDK dependency, mirroring
/// Android's `StubMapSurface`: the CI/config-less implementation the shell is
/// developed and tested against until the real Mapbox surface drops in behind
/// the same protocol. Deterministic for tests — construct with an explicit
/// `initialState` (and `autoLoad: false`) to pin the load state.
///
/// ``recenterCount`` and the last ``userMarker`` are observable so unit tests
/// can assert the shell wires the floating controls to the right hooks.
///
/// `@Observable` so SwiftUI views (the future shell chrome) re-render when
/// the surface's state changes — the iOS analogue of the `StateFlow`s
/// Android's stub exposes.
@Observable
@MainActor
final class StubMapSurface: MapSurface {
    private(set) var loadState: MapLoadState

    private(set) var userMarker: MapUserMarker?

    // Mirrors the real surface's defaults: traffic off, day light preset,
    // 3D camera on.
    private(set) var trafficEnabled: Bool = false
    private(set) var mapMode: MapMode = .day
    private(set) var is3D: Bool = true

    /// The stub has no rotatable camera: bearing is a constant north-up 0.
    let bearing: Double = 0

    private(set) var routeOverlay: MapRouteOverlay?
    private(set) var incidentMarkers: [MapIncidentMarker] = []
    private(set) var eventMarkers: [MapEventMarker] = []
    private(set) var crownMarkers: [MapCrownMarker] = []
    private(set) var billboardMarkers: [MapBillboardMarker] = []

    private(set) var placeRequest: MapPlaceRequest?
    private(set) var incidentTap: String?
    private(set) var eventTap: String?
    private(set) var crownTap: String?
    private(set) var billboardTap: String?

    // The stub has no camera, so it never has a position to report and never
    // projects anything: the marker overlays simply draw nothing on it, which
    // is exactly right for CI and the config-less build. Pinnable via
    // `setCameraSnapshotForTest`.
    private(set) var cameraSnapshot: MapCameraSnapshot?

    /// Last value passed to ``setConvoyFit(points:focusEnabled:)`` —
    /// observable so tests can assert the wiring.
    private(set) var convoyFit: [MapPoint]?

    /// Last `focusEnabled` passed to ``setConvoyFit(points:focusEnabled:)`` —
    /// observable for tests.
    private(set) var convoyFocusEnabled: Bool = false

    /// Last point passed to ``centerOn(_:)`` — observable so tests can assert
    /// the "Go to location" wiring.
    private(set) var centeredOn: MapPoint?

    /// Last non-empty seed passed to ``seedBreadcrumb(_:)`` — observable so
    /// tests can assert the restore wiring.
    private(set) var seededBreadcrumb: [MapPoint] = []

    /// Last value passed to ``setFollowMeTrail(_:)`` — observable so tests
    /// can assert the wiring.
    private(set) var followMeTrail: [MapPoint]?

    /// Number of ``recenter()`` calls — used by tests to assert the wiring.
    private(set) var recenterCount: Int = 0

    /// How many times a north-up reset was requested, by either
    /// ``resetNorth()`` or ``recenterNorthUp()``. The stub has no rotatable
    /// camera, so the count is the only observable the reset leaves behind.
    private(set) var resetNorthCount: Int = 0

    /// The compass orientation mode last applied via ``setCompassMode(_:)``.
    /// Starts north-up (the shell's default); observable so tests can assert
    /// the compass toggles it.
    private(set) var compassMode: MapCompassMode = .northUp

    /// How many times ``setCompassMode(_:)`` actually CHANGED the mode —
    /// observable so a test can assert a tap flipped it exactly once (and
    /// that a redundant re-push of the same mode did nothing).
    private(set) var compassModeChanges: Int = 0

    /// Last value passed to ``setActive(_:)`` — the stub has no render
    /// surface or GPS to stand down, so it just records the call for tests
    /// asserting that the shell deactivates the map while another page covers
    /// it. Starts active: the shell opens on the Map tab.
    private(set) var isActive: Bool = true

    /// The last resting zoom pushed via ``setBrowsingZoom(_:)`` — the stub
    /// has no camera to move, so it records the value for tests asserting the
    /// shell wires the slider through. Starts at the default resting zoom,
    /// matching an untouched slider.
    private(set) var browsingZoom: Double = StubMapSurface.defaultBrowsingZoom

    /// Whether ``simulateInitialLoadIfNeeded()`` should actually simulate the
    /// tile load. False pins the load state for deterministic tests.
    private let autoLoad: Bool

    // Test hook: the projection the stub should report, or nil for "no map".
    @ObservationIgnored
    private var projectionForTest: ((Double, Double) -> MapScreenPoint?)?

    // The fixed visible radius the stub reports (metres). The stub has no
    // camera to measure, so it returns a sane constant rather than nil,
    // keeping the config-less / CI build's incident query deterministic.
    // Overridable for tests.
    @ObservationIgnored
    private var visibleRadiusMetersValue: Double = StubMapSurface.stubVisibleRadiusMeters

    init(initialState: MapLoadState = .loading, autoLoad: Bool = true) {
        self.loadState = initialState
        self.autoLoad = autoLoad
    }

    /// Simulate a short tile/style load once, so the "Loading roads…" line
    /// appears briefly then clears — the iOS analogue of the `LaunchedEffect`
    /// Android's stub runs when its map content enters composition. The
    /// (future) map view calls this from its `.task` when it appears. Does
    /// nothing when `autoLoad` is false, so tests can pin the state
    /// deterministically; cancelling the surrounding task before the delay
    /// elapses leaves the state untouched.
    ///
    /// `delay` defaults to the production 700 ms; tests exercising the
    /// loading→loaded transition pass `.zero` so the suite stays fast.
    func simulateInitialLoadIfNeeded(
        delay: Duration = .milliseconds(StubMapSurface.stubLoadMillis)
    ) async {
        guard autoLoad, loadState == .loading else { return }
        do {
            try await Task.sleep(for: delay)
        } catch {
            // Cancelled before the simulated load finished — mirror the
            // cancelled LaunchedEffect and leave the state untouched.
            return
        }
        loadState = .loaded
    }

    /// Test/impl hook to force the load state (e.g. after tiles finish).
    func markLoaded() {
        loadState = .loaded
    }

    /// A FIXED sensible radius (no camera to measure), so the incident layer
    /// still queries a sane area on the config-less build. Deterministic on
    /// purpose.
    func visibleRadiusMeters() -> Double? {
        visibleRadiusMetersValue
    }

    /// Test hook: pin the visible radius the stub reports.
    func setVisibleRadiusForTest(_ radiusMeters: Double) {
        visibleRadiusMetersValue = radiusMeters
    }

    /// Test hook: pin a camera snapshot so overlay logic can be exercised
    /// off-device.
    func setCameraSnapshotForTest(_ snapshot: MapCameraSnapshot?) {
        cameraSnapshot = snapshot
    }

    /// Test hook: stand in for the renderer's coordinate→pixel projection,
    /// so overlay logic can be driven without a real Mapbox surface. Nil (the
    /// default) keeps the stub's "there is no map" behaviour.
    func setProjectionForTest(_ projection: ((Double, Double) -> MapScreenPoint?)?) {
        projectionForTest = projection
    }

    /// No camera on the stub, so nothing can be projected — unless a test has
    /// installed a projection via ``setProjectionForTest(_:)``. A non-finite
    /// projection returns nil, per the ``MapProjection`` contract.
    func screenPositionFor(latitude: Double, longitude: Double) -> MapScreenPoint? {
        guard let point = projectionForTest?(latitude, longitude),
              point.x.isFinite, point.y.isFinite
        else { return nil }
        return point
    }

    func setConvoyFit(points: [MapPoint]?, focusEnabled: Bool) {
        convoyFit = points
        convoyFocusEnabled = focusEnabled
    }

    func centerOn(_ point: MapPoint) {
        // No camera on the stub — just record the request so the "Go to
        // location" wiring can be asserted off-device.
        centeredOn = point
    }

    func recenter() {
        recenterCount += 1
    }

    func emitLongPress(_ point: MapPoint) {
        placeRequest = MapPlaceRequest(point: point, name: nil)
    }

    func emitPlaceTap(_ point: MapPoint, name: String?) {
        placeRequest = MapPlaceRequest(point: point, name: name)
    }

    func consumePlaceRequest() {
        placeRequest = nil
    }

    func emitIncidentTap(_ incidentId: String) {
        incidentTap = incidentId
    }

    func consumeIncidentTap() {
        incidentTap = nil
    }

    func emitEventTap(_ eventId: String) {
        eventTap = eventId
    }

    func consumeEventTap() {
        eventTap = nil
    }

    func emitCrownTap(_ spawnId: String) {
        crownTap = spawnId
    }

    func consumeCrownTap() {
        crownTap = nil
    }

    func emitBillboardTap(_ billboardId: String) {
        billboardTap = billboardId
    }

    func consumeBillboardTap() {
        billboardTap = nil
    }

    /// No rotatable camera on the stub, so resetting to north only counts.
    func resetNorth() {
        resetNorthCount += 1
    }

    /// Counts as BOTH a re-centre and a north reset, which is what makes the
    /// compass's re-centre assertable: a compass wired to ``resetNorth()``
    /// alone leaves ``recenterCount`` at 0.
    func recenterNorthUp() {
        recenterCount += 1
        resetNorthCount += 1
    }

    /// No rotatable camera on the stub, so a mode change only records the new
    /// mode — and MIRRORS the real surface's user-requested re-centre so the
    /// "the compass still re-centres on the user, and returning to north
    /// still resets north" guarantees stay assertable off-device: a real
    /// change bumps ``recenterCount``, and switching to north-up also bumps
    /// ``resetNorthCount``. Re-setting the same mode is a no-op (matches the
    /// real surface not forcing a camera move when the shell re-pushes an
    /// unchanged mode).
    func setCompassMode(_ mode: MapCompassMode) {
        guard mode != compassMode else { return }
        compassMode = mode
        compassModeChanges += 1
        recenterCount += 1
        if mode == .northUp { resetNorthCount += 1 }
    }

    /// No device/GPS on the stub, so there is no location component to
    /// refresh.
    func refreshLocationComponent() {}

    func setActive(_ active: Bool) {
        isActive = active
    }

    func setUserMarker(_ marker: MapUserMarker?) {
        userMarker = marker
    }

    func setTrafficEnabled(_ enabled: Bool) {
        trafficEnabled = enabled
    }

    func setMapMode(_ mode: MapMode) {
        mapMode = mode
    }

    func set3DEnabled(_ enabled: Bool) {
        is3D = enabled
    }

    func setBrowsingZoom(_ zoom: Double) {
        browsingZoom = zoom
    }

    func setRouteOverlay(_ overlay: MapRouteOverlay?) {
        routeOverlay = overlay
    }

    func seedBreadcrumb(_ points: [MapPoint]) {
        // No renderer on the stub — just record the request so the restore
        // wiring can be asserted off-device. Mirror the real surface's
        // empty-array no-op.
        guard !points.isEmpty else { return }
        seededBreadcrumb = points
    }

    func setFollowMeTrail(_ points: [MapPoint]?) {
        followMeTrail = points
    }

    func setIncidentMarkers(_ markers: [MapIncidentMarker]) {
        incidentMarkers = markers
    }

    func setEventMarkers(_ markers: [MapEventMarker]) {
        eventMarkers = markers
    }

    func setCrownMarkers(_ markers: [MapCrownMarker]) {
        crownMarkers = markers
    }

    func setBillboardMarkers(_ markers: [MapBillboardMarker]) {
        billboardMarkers = markers
    }

    /// How long the simulated tile/style load takes — long enough for the
    /// "Loading roads…" line to be seen, short enough not to feel broken.
    /// Matches Android's `STUB_LOAD_MILLIS`.
    private static let stubLoadMillis = 700

    /// A city-scale default the stub reports for the visible radius: enough
    /// to populate the incident layer around the default camera without a
    /// real map. Matches Android's `STUB_VISIBLE_RADIUS_METERS`.
    private static let stubVisibleRadiusMeters: Double = 15_000

    /// The resting zoom an untouched slider produces — Android sources this
    /// from `MapZoomPreference.DEFAULT_ZOOM` (= `MapMarkers.OWN_MARKER_ZOOM`,
    /// 16.0); the preference itself is not ported yet, so the value lives
    /// here until it is. Keep the two in sync.
    private static let defaultBrowsingZoom: Double = 16.0
}
