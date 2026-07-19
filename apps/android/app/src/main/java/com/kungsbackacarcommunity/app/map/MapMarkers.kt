package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.live.LiveMainCar
import com.kungsbackacarcommunity.app.live.LiveMarker

/**
 * Pure map/camera logic for the Map slice (Phase 12 slice 7 + live-markers
 * follow-up).
 *
 * No Android or Mapbox imports so it is JVM-unit-testable and reused by the
 * screen. Holds the default camera used before/without a live fix, the marker
 * model, and the rules for turning raw live positions into that model.
 *
 * Markers are built from per-uid `liveLocation/{uid}/latest` reads only — there
 * is NO collection scan (the RTDB rules grant per-uid reads only). The caller's
 * OWN marker is drawn in the primary colour; other members (e.g. a group-drive
 * roster) in the secondary colour. Members who stopped sharing have a null
 * marker and are simply dropped.
 */

/** A lng/lat position plus zoom — enough to drive a Mapbox camera. */
data class MapCameraPosition(
    val longitude: Double,
    val latitude: Double,
    val zoom: Double,
)

/** Whether a marker is the caller's own position or another member's. */
enum class MapMarkerKind { OWN, OTHER }

/**
 * A single map marker. [kind] selects the colour (own vs other) and [uid]/
 * [displayName] carry identity for future labelling; only lng/lat are needed to
 * draw the circle.
 */
data class MapMarker(
    val longitude: Double,
    val latitude: Double,
    val kind: MapMarkerKind = MapMarkerKind.OWN,
    val uid: String? = null,
    val displayName: String? = null,
    /**
     * The sharer's main car (denormalized onto the live marker), or null when
     * they have none. Carried through for a marker callout to show which car the
     * pin is; the current circle-annotation renderer does not draw it yet.
     */
    val mainCar: LiveMainCar? = null,
)

object MapMarkers {
    /**
     * Default camera when no live fix is available: centered on Kungsbacka
     * (the community's home town) at a neighbourhood-level zoom so the map
     * opens close to the user rather than surveying the whole town. Longitude
     * first to match Mapbox's lng/lat ordering.
     */
    val DEFAULT_CAMERA: MapCameraPosition =
        MapCameraPosition(longitude = 12.0757, latitude = 57.4874, zoom = 15.0)

    /** Zoom used once we have a position to focus on (street/neighbourhood level). */
    const val OWN_MARKER_ZOOM: Double = 16.0

    /**
     * Default camera pitch (degrees) giving the map its tilted, 3D perspective.
     * The Mapbox Standard style renders 3D buildings/terrain when the camera is
     * pitched; keeping it here as a single value lets the map surface apply the
     * same tilt to the initial, first-fix, and recenter cameras — and lets the
     * layers toggle flip between 3D ([DEFAULT_PITCH]) and flat 2D ([FLAT_PITCH])
     * at runtime.
     */
    const val DEFAULT_PITCH: Double = 45.0

    /** Flat, top-down pitch (2D) for the layers toggle's non-3D mode. */
    const val FLAT_PITCH: Double = 0.0

    /**
     * Marker model for the caller's own position, or null when no coordinate
     * is available (nothing to draw). Kept trivial and total so the screen can
     * call it unconditionally.
     */
    fun ownMarker(longitude: Double?, latitude: Double?): MapMarker? {
        if (longitude == null || latitude == null) return null
        return MapMarker(longitude = longitude, latitude = latitude, kind = MapMarkerKind.OWN)
    }

    /** Maps a live [LiveMarker] to a map [MapMarker] of the given [kind]. */
    fun markerOf(marker: LiveMarker?, kind: MapMarkerKind): MapMarker? {
        marker ?: return null
        return MapMarker(
            longitude = marker.longitude,
            latitude = marker.latitude,
            kind = kind,
            uid = marker.uid,
            displayName = marker.displayName,
            mainCar = marker.mainCar,
        )
    }

    /**
     * Builds the ordered marker list to draw: the caller's own marker first
     * (when present), then every other member who is currently sharing. Null
     * entries (members who stopped sharing / never had a fix) are dropped, and
     * the own uid is never duplicated among the others.
     */
    fun markers(own: LiveMarker?, others: List<LiveMarker?>): List<MapMarker> {
        val ownMarker = markerOf(own, MapMarkerKind.OWN)
        val ownUid = own?.uid
        val otherMarkers =
            others
                .asSequence()
                .filterNotNull()
                .filter { it.uid != ownUid }
                .distinctBy { it.uid }
                .mapNotNull { markerOf(it, MapMarkerKind.OTHER) }
                .toList()
        return if (ownMarker != null) listOf(ownMarker) + otherMarkers else otherMarkers
    }

    /**
     * The callout text to draw above a [marker]: the sharer's display name on
     * the first line and, when a main car is denormalized onto the marker (see
     * [LiveMainCar]), its "make model" on a second line. Returns null when there
     * is nothing worth labelling (no display name AND no car) so the renderer
     * leaves a position-only pin as a bare circle.
     *
     * [fallbackName] is used as the first line when the marker carries a car but
     * no display name, so the car is never shown orphaned without a who. Blank
     * make/model parts are trimmed away; a car with an empty make+model degrades
     * to just the name line. Pure (no Android/Mapbox types) so it is unit-tested
     * alongside the rest of [MapMarkers] and reused by the map surface.
     *
     * The only intentional line break is the one this function inserts between
     * the name and the car, so every field is [sanitizeLine]-collapsed first:
     * an embedded newline or run of control whitespace in a display name, make,
     * or model becomes a single space and can never inject extra lines into the
     * 1–2 line callout.
     *
     * A blank/whitespace-only [fallbackName] is treated as absent (never emitted
     * as an empty first line): a car with no usable name renders as just the car
     * line, and a marker with neither a usable name nor a car returns null so the
     * renderer draws a bare circle rather than a blank/vertically-shifted callout.
     */
    fun calloutLabel(marker: MapMarker, fallbackName: String): String? {
        val name = sanitizeLine(marker.displayName).takeIf { it.isNotEmpty() }
        val car =
            marker.mainCar
                ?.let { "${sanitizeLine(it.make)} ${sanitizeLine(it.model)}".trim() }
                ?.takeIf { it.isNotEmpty() }
        // The fallback only ever stands in as the "who" for a car — a bare
        // position marker with no real display name is NOT labelled with it. A
        // blank fallback collapses to null so it is never emitted as an empty line.
        return when {
            name != null && car != null -> "$name\n$car"
            name != null -> name
            car != null -> {
                val fallback = sanitizeLine(fallbackName).takeIf { it.isNotEmpty() }
                if (fallback != null) "$fallback\n$car" else car
            }
            else -> null
        }
    }

    /**
     * Collapses every run of whitespace — including embedded newlines, tabs, and
     * other control whitespace — to a single space and trims the ends, so a value
     * is guaranteed single-line before it is composed into the callout. Null in
     * ⇒ empty string out.
     */
    private fun sanitizeLine(value: String?): String =
        value?.replace(WHITESPACE_RUN, " ")?.trim().orEmpty()

    /** Any run of one-or-more whitespace characters (incl. \n, \r, \t). */
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Camera to show for the given own-position marker: focus on it when
     * present, otherwise fall back to [DEFAULT_CAMERA]. Longitude/latitude are
     * carried through unchanged; only the zoom tightens when we have a fix.
     */
    fun cameraFor(marker: MapMarker?): MapCameraPosition =
        if (marker == null) {
            DEFAULT_CAMERA
        } else {
            MapCameraPosition(
                longitude = marker.longitude,
                latitude = marker.latitude,
                zoom = OWN_MARKER_ZOOM,
            )
        }

    /**
     * Camera for a whole marker list: focus on the caller's own marker when it
     * is present (it is always first), else the first available other marker,
     * else the default town camera. A single focus point (not a bounds fit)
     * keeps this Mapbox-free and deterministic for tests; the own position is
     * the natural centre when the caller is sharing.
     */
    fun cameraForMarkers(markers: List<MapMarker>): MapCameraPosition {
        val focus = markers.firstOrNull { it.kind == MapMarkerKind.OWN } ?: markers.firstOrNull()
        return cameraFor(focus)
    }
}

/**
 * The map's shared MARKER STYLING — the visual language every destination pin in
 * the app is drawn with, wherever it is drawn.
 *
 * Lives here (next to the marker model, no Android/Mapbox imports) rather than
 * inside one renderer because there are now TWO renderers drawing the same
 * concept: the shell's [com.kungsbackacarcommunity.app.shell.MapboxMapSurface]
 * route-preview overlay, and the turn-by-turn navigation map's end-of-route
 * marker. They MUST look identical — a destination that changes appearance the
 * moment you press "Start" reads as a different thing — so the values are stated
 * once and both renderers read them.
 *
 * ARGB ints and pixel-ish radii, matching the Mapbox annotation options that
 * consume them (`withCircleRadius` / `withCircleColor` / `withCircleStroke*`).
 */
object MapMarkerStyle {
    /** Destination pin fill: the app's marker red. */
    const val DEST_MARKER_COLOR: Int = 0xFFD32F2F.toInt()

    /** Destination pin radius. */
    const val DEST_MARKER_RADIUS: Double = 9.0

    /** Destination pin outline width — keeps the dot legible on any basemap. */
    const val DEST_MARKER_STROKE: Double = 2.0

    /** Destination pin outline colour (white). */
    const val DEST_MARKER_STROKE_COLOR: Int = 0xFFFFFFFF.toInt()
}
