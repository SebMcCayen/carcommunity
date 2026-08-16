package com.kungsbackacarcommunity.app.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry for the off-screen convoy-member direction arrows.
 *
 * ## Why this is not maths inside a composable
 * Every hard part of this feature is arithmetic that is wrong in a way you
 * cannot see on a screenshot: an arrow that points correctly while the map is
 * north-up and 30° out the moment the user rotates, or one that points at the
 * mirror image of a member who is behind a tilted camera. None of that is
 * testable through Compose, so all of it lives here as functions of numbers,
 * and the composable only draws what these return.
 *
 * ## Rotation
 * The map is rotatable (and in navigation it is course-up, so it rotates
 * continuously). The arrow's screen angle is therefore NOT derived from a
 * lat/lng delta: it is the member's true geographic bearing from the camera
 * centre MINUS the camera's own bearing — see [screenAngleDegrees]. That
 * subtraction is the entire rotation correction, and it is exact for any camera
 * bearing, including the wrap-around at 0/360.
 *
 * ## Pitch
 * The map is tilted by default (45°). Pitch foreshortens the vertical screen
 * axis: the same ground distance covers fewer pixels near the top of the screen
 * than near the bottom, and beyond the horizon it stops mapping to the screen
 * at all. Two consequences, handled separately:
 *
 * 1. **Direction.** Pitch rotates the camera about a HORIZONTAL axis, so it does
 *    not change a point's azimuth around the vertical axis. The compass
 *    direction the user must look in is pitch-invariant, which is why
 *    [screenAngleDegrees] takes no pitch argument. Placing the arrow by azimuth
 *    also keeps a group of members at the same true bearing on the same edge,
 *    instead of smearing them toward the horizon as a perspective projection
 *    would.
 * 2. **Visibility.** Deciding whether a member is inside the viewport DOES need
 *    pitch, and getting that from first principles means reimplementing the
 *    renderer's projection matrix. So the caller hands us the point the map SDK
 *    itself projected ([ProjectedPoint]) — exact under any rotation, pitch and
 *    zoom — and we only sanity-check and classify it here. See
 *    [isProjectionTrustworthy] for the one trap that projection has.
 */
object ConvoyEdgeGeometry {

    /** Earth radius used for the bearing/distance maths (mean sphere, metres). */
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /**
     * A point the map SDK projected into view pixels. May legitimately be far
     * outside the viewport, and — for a point behind a tilted camera — may be
     * mirrored into the wrong half of the screen entirely (see
     * [isProjectionTrustworthy]).
     */
    data class ProjectedPoint(val x: Float, val y: Float)

    /**
     * Initial great-circle bearing from one coordinate to another, in degrees
     * clockwise from true north, normalised to `[0, 360)`.
     *
     * Initial (forward azimuth) rather than a flat-plane `atan2(dLat, dLng)`:
     * at Swedish latitudes a naive planar angle is several degrees off over a
     * convoy-sized separation because a degree of longitude is only ~0.54 of a
     * degree of latitude in metres, and it degrades further across the date line.
     */
    fun initialBearingDegrees(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val fromLat = Math.toRadians(fromLatitude)
        val toLat = Math.toRadians(toLatitude)
        val deltaLng = Math.toRadians(toLongitude - fromLongitude)
        val y = sin(deltaLng) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLng)
        // atan2(0, 0) is 0 rather than undefined, so two identical coordinates
        // yield bearing 0 instead of NaN. Callers filter that case by distance
        // (see [ConvoyArrowPlanner.MIN_ARROW_DISTANCE_METERS]) — a member at the
        // camera centre is on screen anyway and never gets an arrow.
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    /** Great-circle distance in metres between two coordinates (haversine). */
    fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val fromLat = Math.toRadians(fromLatitude)
        val toLat = Math.toRadians(toLatitude)
        val deltaLat = toLat - fromLat
        val deltaLng = Math.toRadians(toLongitude - fromLongitude)
        // Clamped into [0, 1]: `a` is mathematically a squared sine and cannot
        // leave that range, but floating-point rounding can push it a hair past
        // 1 for near-antipodal points. `sqrt(1 - a)` would then be NaN, and a NaN
        // distance propagates silently into the planner — a member sorted by a
        // NaN distance neither compares nor filters predictably, so an arrow
        // would go missing or point nowhere with nothing to indicate why.
        val a =
            (
                sin(deltaLat / 2) * sin(deltaLat / 2) +
                    cos(fromLat) * cos(toLat) * sin(deltaLng / 2) * sin(deltaLng / 2)
                ).coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * The on-screen angle to draw an arrow at, in degrees clockwise from
     * straight UP the screen, normalised to `[0, 360)`.
     *
     * This is the whole rotation correction: screen-up is whatever compass
     * direction the camera is bearing toward, so subtracting the camera bearing
     * converts a world azimuth into a screen azimuth. North-up ([cameraBearing]
     * 0) leaves the bearing unchanged; a camera bearing of 90° (east up the
     * screen) puts a member due north of us at 270°, i.e. pointing left.
     */
    fun screenAngleDegrees(geographicBearing: Double, cameraBearing: Double): Double =
        normalizeDegrees(geographicBearing - cameraBearing)

    /**
     * Whether a projected point lies inside the viewport, with [marginPx] of
     * slack so a member does not flicker between marker and arrow while sitting
     * exactly on the boundary. The margin SHRINKS the rectangle, so the arrow
     * appears slightly before the member's marker would be clipped.
     */
    fun isInsideViewport(
        point: ProjectedPoint,
        viewportWidth: Float,
        viewportHeight: Float,
        marginPx: Float = 0f,
    ): Boolean =
        point.x.isFinite() &&
            point.y.isFinite() &&
            point.x >= marginPx &&
            point.y >= marginPx &&
            point.x <= viewportWidth - marginPx &&
            point.y <= viewportHeight - marginPx

    /**
     * Whether the SDK's projection of a point can be believed for the
     * inside/outside decision.
     *
     * The trap: on a PITCHED map, a coordinate behind the camera (or beyond the
     * horizon) has no honest screen position, and the projection matrix folds it
     * back into view — typically MIRRORED through the centre, landing it near
     * the top of the screen where the horizon is. Taken at face value, a convoy
     * member who is directly behind you renders as a marker in front of you.
     *
     * The check is a cross-examination rather than a horizon calculation: we
     * already know, independently and exactly, which way the member lies —
     * [expectedScreenAngle], from their compass bearing. A true projection of a
     * point in front of the camera always sits on the same side of centre as
     * that bearing; pitch foreshortens the RADIUS strongly but can only bend the
     * azimuth modestly, and never past a right angle. A mirrored point lands at
     * roughly the opposite angle. So a disagreement of more than
     * [MAX_ANGLE_DISAGREEMENT_DEGREES] means the projection folded, and the
     * member is treated as off-screen — which is where they actually are.
     *
     * ## Why the centre is trusted over a WHOLE region, not a single pixel (#867)
     * Near the viewport centre the cross-examination has to be switched OFF, and
     * over a wider region than the one-pixel [CENTRE_EPSILON_PX] radius it used to
     * skip. The angle
     * it checks against, [expectedScreenAngle], is the member's bearing from the
     * camera centre — and as the member approaches the centre that separation
     * shrinks to a few metres, at which point a sub-metre GPS/rounding jitter (and
     * the settled snapshot's own ~1 m quantisation of the camera centre it is
     * measured from) swings that bearing through a full turn. The projected pixel,
     * by contrast, stays exactly where the live map puts it. So a marker the user
     * has deliberately zoomed in on — sitting right at the centre, which is the
     * whole point of zooming toward someone — had a meaningless "expected" angle
     * cross-examined against an honest pixel, disagreed by more than a right
     * angle, and was culled: the icon vanished at some zoom levels and came back
     * at others as the pixel drifted back across the epsilon. That is issue #867.
     *
     * A genuine behind-camera fold cannot hide inside this region: it lands at or
     * above the horizon, which even at this app's maximum pitch (45°,
     * [com.kungsbackacarcommunity.app.map.MapMarkers.DEFAULT_PITCH]) is empirically
     * the better part of the viewport HEIGHT away from the centre. Trusting a disc
     * of radius [CENTRE_TRUST_FRACTION] of the SMALLER viewport dimension therefore
     * covers the metres-from-centre zone where the bearing is noise while staying
     * comfortably inside the fold's landing distance, so the fold check keeps
     * catching the case it exists for.
     */
    fun isProjectionTrustworthy(
        point: ProjectedPoint,
        viewportWidth: Float,
        viewportHeight: Float,
        expectedScreenAngle: Double,
    ): Boolean {
        if (!point.x.isFinite() || !point.y.isFinite()) return false
        val dx = point.x - viewportWidth / 2f
        val dy = point.y - viewportHeight / 2f
        val centreTrustRadiusPx =
            maxOf(CENTRE_EPSILON_PX, CENTRE_TRUST_FRACTION * minOf(viewportWidth, viewportHeight))
        if (hypot(dx, dy) <= centreTrustRadiusPx) return true
        // Screen angle of the projected offset, same convention as
        // [screenAngleDegrees]: 0 = up, clockwise positive. Screen y grows
        // downward, hence -dy.
        val actual = normalizeDegrees(Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())))
        return angleDifferenceDegrees(actual, expectedScreenAngle) <= MAX_ANGLE_DISAGREEMENT_DEGREES
    }

    /**
     * Where on the viewport edge an arrow at [angleDegrees] should sit: the
     * point where a ray cast from the viewport centre in that direction meets a
     * rectangle inset by [insetPx] on every side.
     *
     * The inset is what keeps the arrow (and its member photo) fully on screen
     * and clear of the rounded corners, rather than half-clipped by the edge it
     * is pinned to. A viewport too small to contain the inset collapses to the
     * centre instead of producing an inverted rectangle.
     */
    fun edgePoint(
        angleDegrees: Double,
        viewportWidth: Float,
        viewportHeight: Float,
        insetPx: Float,
    ): ProjectedPoint {
        val centreX = viewportWidth / 2f
        val centreY = viewportHeight / 2f
        val halfWidth = centreX - insetPx
        val halfHeight = centreY - insetPx
        if (halfWidth <= 0f || halfHeight <= 0f) return ProjectedPoint(centreX, centreY)

        val radians = Math.toRadians(normalizeDegrees(angleDegrees))
        val dx = sin(radians)
        // Screen y grows downward while the angle is measured from screen-up.
        val dy = -cos(radians)

        // Distance along the ray to each of the two candidate walls; the nearer
        // wall is the one actually hit. A zero component never hits its pair of
        // walls, so its candidate is infinite and loses the min().
        val toVerticalWall = if (abs(dx) < RAY_EPSILON) Double.MAX_VALUE else halfWidth / abs(dx)
        val toHorizontalWall = if (abs(dy) < RAY_EPSILON) Double.MAX_VALUE else halfHeight / abs(dy)
        val t = min(toVerticalWall, toHorizontalWall)

        return ProjectedPoint(
            x = (centreX + dx * t).toFloat(),
            y = (centreY + dy * t).toFloat(),
        )
    }

    /**
     * Ground metres one screen pixel spans at [latitude] and [zoom] on the
     * web-mercator basemap. The standard Mapbox/Google resolution:
     * `156543.03 * cos(lat) / 2^zoom` at 256-px tiles.
     *
     * Used to turn a coordinate round-trip mismatch (see [projectionRoundTrips])
     * into a pixel-scale tolerance, so the same absolute slack is neither
     * hair-trigger when zoomed right in nor useless when zoomed out — one pixel is
     * a metre downtown and hundreds of metres at a country view.
     */
    fun metersPerPixel(latitude: Double, zoom: Double): Double =
        156_543.03392 * cos(Math.toRadians(latitude)) / Math.pow(2.0, zoom)

    /**
     * Whether a coordinate → pixel → coordinate ROUND TRIP through the SDK's
     * projection landed back where it started.
     *
     * This is the deterministic cure for the "off-screen live user stuck in the
     * top-left corner" bug that the [isProjectionTrustworthy] angle heuristic only
     * *usually* caught. On a pitched map a coordinate behind the camera (or beyond
     * the horizon, or off the projectable globe) has no honest screen position, and
     * `pixelForCoordinate` folds OR clamps it back into view — sometimes mirrored
     * through the centre (which the angle check catches), but sometimes clamped to
     * a fixed viewport CORNER such as the origin (0, 0), whose direction is
     * independent of the target's bearing, so roughly half of those slipped past
     * the ≤90° cross-examination and pinned a chip to the top-left.
     *
     * Unprojecting the folded/clamped pixel does not recover the original
     * coordinate — it returns the real place that pixel is showing, which is far
     * away — whereas a genuinely on-screen point round-trips back to itself within
     * a pixel. So the coordinate-space mismatch is a bearing-INDEPENDENT,
     * zoom-independent test that the projection can be believed.
     *
     * @param metersPerPixel the current [metersPerPixel]; the tolerance is
     *   [tolerancePixels] of these, floored at [MIN_ROUND_TRIP_TOLERANCE_METERS] so
     *   sub-pixel rounding at high zoom never trips it.
     */
    fun projectionRoundTrips(
        originalLatitude: Double,
        originalLongitude: Double,
        unprojectedLatitude: Double,
        unprojectedLongitude: Double,
        metersPerPixel: Double,
        tolerancePixels: Double = ROUND_TRIP_TOLERANCE_PX,
    ): Boolean {
        if (!originalLatitude.isFinite() || !originalLongitude.isFinite()) return false
        if (!unprojectedLatitude.isFinite() || !unprojectedLongitude.isFinite()) return false
        val mpp = if (metersPerPixel.isFinite() && metersPerPixel > 0.0) metersPerPixel else 0.0
        val tolerance = (mpp * tolerancePixels).coerceAtLeast(MIN_ROUND_TRIP_TOLERANCE_METERS)
        val drift =
            distanceMeters(
                fromLatitude = originalLatitude,
                fromLongitude = originalLongitude,
                toLatitude = unprojectedLatitude,
                toLongitude = unprojectedLongitude,
            )
        return drift <= tolerance
    }

    /** Wraps any angle into `[0, 360)`. */
    fun normalizeDegrees(degrees: Double): Double {
        val wrapped = degrees % 360.0
        return if (wrapped < 0) wrapped + 360.0 else wrapped
    }

    /** Smallest absolute separation between two angles, in `[0, 180]`. */
    fun angleDifferenceDegrees(a: Double, b: Double): Double {
        val diff = abs(normalizeDegrees(a) - normalizeDegrees(b))
        return if (diff > 180.0) 360.0 - diff else diff
    }

    /**
     * Angular slack allowed between the bearing-derived direction and the
     * SDK-projected direction before the projection is judged to have folded.
     * A right angle: pitch cannot bend a genuine in-front projection that far,
     * and a mirrored point misses by roughly 180°, so the two populations are
     * cleanly separated and the exact threshold is not delicate.
     */
    const val MAX_ANGLE_DISAGREEMENT_DEGREES: Double = 90.0

    /**
     * One-pixel radius around the viewport centre inside which a screen angle is
     * meaningless. Retained only as the floor of the much wider
     * [CENTRE_TRUST_FRACTION] disc (see [isProjectionTrustworthy]).
     */
    const val CENTRE_EPSILON_PX: Float = 1f

    /**
     * Fraction of the SMALLER viewport dimension around the centre within which a
     * projection is trusted outright, skipping the bearing cross-examination (see
     * [isProjectionTrustworthy] for the full reasoning behind #867).
     *
     * Sized to sit between the two distances that bracket it. Below: the
     * few-metres-from-centre zone where [expectedScreenAngle] is dominated by
     * jitter — at ordinary browsing zooms a few metres is only tens of pixels, so
     * even a small fraction covers it. Above: the fold's landing distance — a
     * behind-camera point lands near the horizon, which at this app's 45° pitch is
     * upward of 40% of the viewport height from the centre, so 15% of the smaller
     * dimension keeps a better-than-2× margin before the trusted disc could ever
     * reach a genuine fold.
     */
    const val CENTRE_TRUST_FRACTION: Float = 0.15f

    /**
     * Round-trip slack in pixels (see [projectionRoundTrips]). A few pixels
     * absorbs the SDK's own coordinate↔pixel rounding for a genuine on-screen
     * point; a fold/clamp misses by orders of magnitude more, so the exact value
     * is not delicate.
     */
    const val ROUND_TRIP_TOLERANCE_PX: Double = 4.0

    /**
     * Floor on the round-trip tolerance in metres, so at very high zoom (a pixel
     * spanning centimetres) the pixel-scaled tolerance does not collapse below the
     * projection's own arithmetic noise and reject a point that is genuinely there.
     */
    const val MIN_ROUND_TRIP_TOLERANCE_METERS: Double = 2.0

    // Below this, a ray direction component is treated as exactly zero (an
    // axis-aligned ray), which avoids dividing by a denormal and shooting the
    // edge point to infinity.
    private const val RAY_EPSILON: Double = 1e-9
}
