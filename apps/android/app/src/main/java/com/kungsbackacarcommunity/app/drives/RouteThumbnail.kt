package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.PolylineCodec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A point projected into a thumbnail's drawing box, in pixels from its top-left
 * corner. Deliberately NOT `androidx.compose.ui.geometry.Offset`: keeping the
 * projection's output a plain pair of floats is what lets the whole of
 * [RouteThumbnail] be unit-tested on the JVM with no Compose (and no device) in
 * the picture. The composable converts to a `Path` once, off the draw scope.
 */
data class ThumbnailPoint(val x: Float, val y: Float)

/**
 * Turns a drive's stored `routeThumbnail` — the ~64-point encoded polyline
 * `drives.save` derives from the recording — into a path the History card can
 * draw on a `Canvas`.
 *
 * ## Why this exists at all
 * A History card must cost nothing extra. The full route lives in member-gated
 * Cloud Storage and a card is a row in a scrolling list, so fetching or
 * decoding one per card, or instantiating a map per card, is out of the
 * question. The backend therefore puts a few hundred bytes of simplified
 * geometry on the ride document the list ALREADY reads, and this object turns
 * that string into ~64 points and a fitted box. No I/O, no map, no allocation
 * in the draw scope.
 *
 * ## Longitude is not latitude
 * A degree of longitude is a degree of latitude times cos(latitude). At
 * Kungsbacka's ~57.5°N that factor is ~0.54 — so projecting raw degrees into
 * the box would stretch every route to nearly twice its true width and turn a
 * square block of streets into a wide rectangle. The projection scales
 * longitude by cos(mean latitude) before fitting, which is exact enough over
 * the span of a single drive.
 *
 * ## Degenerate routes are the common case, not the edge
 * Every drive saved before the thumbnail existed has no polyline at all (there
 * is no backfill), and plenty of recordings are a phone that sat parked. Every
 * such case returns null from [project] and the card draws its placeholder —
 * one path, never an empty box and never a crash.
 *
 * Pure Kotlin (no Android, Compose or Firebase types) so all of it is unit
 * tested; only the `Canvas` call itself is device-only.
 */
object RouteThumbnail {

    /** Coordinate precision the backend encodes with (route-thumbnail.ts). */
    const val POLYLINE_PRECISION: Double = 1e5

    /** A polyline needs two points to be a line. */
    const val MIN_DRAWABLE_POINTS: Int = 2

    /**
     * Smallest bounding-box extent, in metres, still worth drawing as a route.
     *
     * Below this the fit would blow a few metres of GPS jitter up to fill the
     * whole box — a drive that never left the driveway would render as a
     * confident scribble that looks like a route and is not one. A stationary
     * recording says "no route overview" instead. 25 m is comfortably inside a
     * short street and comfortably outside a parked phone's wander.
     */
    const val MIN_EXTENT_METERS: Double = 25.0

    /** Metres per degree of latitude (mean spherical Earth). */
    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * Coordinate bounds every real fix satisfies.
     *
     * A corrupt polyline that happens to decode cleanly still produces
     * coordinates: `"not a polyline!!"` decodes to a latitude of ~3623°. Those
     * are finite, so a finiteness check passes them, and the fit then scales
     * them into the box and draws a confident line that is not a route. The
     * backend only ever encodes points `drives.save` already validated to
     * ±90 / ±180, so anything outside is corruption by definition and gets the
     * placeholder — the same answer a stationary recording gets.
     */
    private const val MAX_ABS_LATITUDE = 90.0
    private const val MAX_ABS_LONGITUDE = 180.0

    /**
     * Decodes a stored thumbnail polyline, or an empty list when there is none
     * — including when the stored value is corrupt.
     *
     * Delegates to the app's existing [PolylineCodec] — the decoder already
     * shipped for Mapbox route geometry — rather than adding a second one that
     * could drift from the encoder. Only the precision differs (1e5 here, the
     * backend's; 1e6 for Mapbox), which the codec already takes as a parameter.
     *
     * ## Why the catch is here and not in the codec
     * [PolylineCodec] is not a total function and is not documented as one. A
     * polyline varint pair is latitude-then-longitude, so a string that ends
     * BETWEEN the two makes the decoder read past its last character and throw
     * `StringIndexOutOfBoundsException`. A single `"_"`, or one complete
     * latitude with nothing after it, is enough. A long run of garbage does
     * NOT do this — there is always another character left to consume, so it
     * merely decodes to nonsense — which is exactly why testing only a garbled
     * string never reached this path.
     *
     * The value arrives from Firestore and is decoded while composing a row in
     * the History LIST, so an escaping throw would not blank one card: it would
     * take down the whole History screen for anyone holding a single corrupt
     * document. "Corruption degrades to the placeholder" is this object's
     * stated contract, so this is the boundary that enforces it. Widening
     * [PolylineCodec] itself would instead change what its other caller sees —
     * Mapbox route geometry, where a malformed response is a real error worth
     * surfacing rather than silently drawing nothing.
     *
     * Catching [RuntimeException] rather than the narrower index error is
     * deliberate: the whole point of the boundary is that no decoder failure,
     * present or future, can reach the list.
     */
    fun decode(encoded: String?): List<LatLng> {
        if (encoded.isNullOrBlank()) return emptyList()
        return try {
            PolylineCodec.decode(encoded, POLYLINE_PRECISION)
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    /**
     * Fits [points] into a [width] x [height] box, inset by [padding] on every
     * side, preserving the route's true aspect ratio.
     *
     * Returns null — meaning "draw the placeholder" — when there is no route to
     * show: fewer than [MIN_DRAWABLE_POINTS] points, a box too small to draw
     * in, a non-finite or off-globe coordinate, or a route whose whole extent
     * is under [MIN_EXTENT_METERS].
     */
    fun project(
        points: List<LatLng>,
        width: Float,
        height: Float,
        padding: Float,
    ): List<ThumbnailPoint>? {
        if (points.size < MIN_DRAWABLE_POINTS) return null

        val innerWidth = width - 2 * padding
        val innerHeight = height - 2 * padding
        if (!innerWidth.isFinite() || !innerHeight.isFinite()) return null
        if (innerWidth <= 0f || innerHeight <= 0f) return null

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (point in points) {
            if (!point.latitude.isFinite() || !point.longitude.isFinite()) return null
            if (abs(point.latitude) > MAX_ABS_LATITUDE) return null
            if (abs(point.longitude) > MAX_ABS_LONGITUDE) return null
            minLat = min(minLat, point.latitude)
            maxLat = max(maxLat, point.latitude)
            minLon = min(minLon, point.longitude)
            maxLon = max(maxLon, point.longitude)
        }

        // cos(mean latitude): the whole reason a Swedish route does not come
        // out ~1.9x too wide. Guarded against a degenerate pole value so the
        // scale can never be zero or negative.
        val lonScale = max(cos(Math.toRadians((minLat + maxLat) / 2.0)), 1e-6)

        // Extent in METRES, so the "is this actually a route" test means the
        // same thing everywhere on Earth rather than depending on latitude.
        val spanYMeters = (maxLat - minLat) * METERS_PER_DEGREE
        val spanXMeters = (maxLon - minLon) * METERS_PER_DEGREE * lonScale
        if (max(spanXMeters, spanYMeters) < MIN_EXTENT_METERS) return null

        // One scale for both axes = the route keeps its shape; the axis that
        // does not fill the box is centred in it. A perfectly straight
        // north-south (or east-west) route has zero span on one axis, which
        // must not become a division by zero — that axis simply does not
        // constrain the fit.
        val scaleX = if (spanXMeters > 0.0) innerWidth / spanXMeters else Double.MAX_VALUE
        val scaleY = if (spanYMeters > 0.0) innerHeight / spanYMeters else Double.MAX_VALUE
        val scale = min(scaleX, scaleY)
        if (!scale.isFinite() || scale <= 0.0) return null

        val drawnWidth = spanXMeters * scale
        val drawnHeight = spanYMeters * scale
        val originX = padding + (innerWidth - drawnWidth) / 2.0
        val originY = padding + (innerHeight - drawnHeight) / 2.0

        return points.map { point ->
            val xMeters = (point.longitude - minLon) * METERS_PER_DEGREE * lonScale
            // Latitude grows north, canvas y grows DOWN: measure from the top
            // (maxLat) so the drawn route is not upside down.
            val yMeters = (maxLat - point.latitude) * METERS_PER_DEGREE
            ThumbnailPoint(
                x = (originX + xMeters * scale).toFloat(),
                y = (originY + yMeters * scale).toFloat(),
            )
        }
    }

    /**
     * Convenience for the card: decode + project in one call, null when the
     * drive has no drawable thumbnail (including the very common case of a
     * drive saved before the field existed, where [encoded] is null).
     */
    fun pathFor(
        encoded: String?,
        width: Float,
        height: Float,
        padding: Float,
    ): List<ThumbnailPoint>? = project(decode(encoded), width, height, padding)
}
