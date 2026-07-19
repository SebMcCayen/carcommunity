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
        val a =
            sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(fromLat) * cos(toLat) * sin(deltaLng / 2) * sin(deltaLng / 2)
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
     * Points within [CENTRE_EPSILON_PX] of the viewport centre have no
     * meaningful angle and are trusted: they are on screen by definition.
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
        if (hypot(dx, dy) <= CENTRE_EPSILON_PX) return true
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

    /** Radius around the viewport centre inside which a screen angle is meaningless. */
    const val CENTRE_EPSILON_PX: Float = 1f

    // Below this, a ray direction component is treated as exactly zero (an
    // axis-aligned ray), which avoids dividing by a denormal and shooting the
    // edge point to infinity.
    private const val RAY_EPSILON: Double = 1e-9
}
