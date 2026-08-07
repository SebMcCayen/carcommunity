package com.kungsbackacarcommunity.app.navigation.turnbyturn

import com.kungsbackacarcommunity.app.shell.MapCompassMode

/**
 * Pure (Android-free, SDK-free) decision for the turn-by-turn follow camera's
 * BEARING — "which way is up while I drive?".
 *
 * Pure for the same reason [NavChrome], [NavHandoff] and [NavProgressFormat]
 * are: everything under `src/nav` needs the Mapbox **Navigation** SDK and a
 * `MAPBOX_DOWNLOADS_TOKEN` to compile, so it can only be built by CI's
 * `nav-variant-compile` job and can never be unit-tested. The decision of which
 * bearing SOURCE the camera should follow is arithmetic that needs none of that,
 * so it lives here, in `src/main`, with tests — and the navigation engine only
 * calls it, keeping the actual SDK camera wiring thin.
 *
 * ## The reported bug
 * "During turn-by-turn navigation the map is not oriented to the direction of
 * travel — it's rotated/skewed relative to where I'm actually driving, so 'up' on
 * screen isn't 'ahead on the road.'" A navigation follow camera should be
 * COURSE-UP: the map bearing tracks the driving heading so the road ahead points
 * up and the puck sits naturally.
 *
 * The follow camera's bearing is the vehicle's course (its GPS heading). That is
 * exactly right WHILE MOVING, and the Mapbox Navigation SDK's own viewport data
 * source already produces it — blending the location course with the route
 * geometry — so the common case simply hands the bearing to the SDK ([NULL_HAND
 * back][followingBearingOverride] returns `null`).
 *
 * The gap is at LOW SPEED. A GPS fix's course is only meaningful once the vehicle
 * is actually moving: standing at a light, crawling in a queue or the instant
 * before pulling away, the reported course is stale, jittery or absent, and a
 * camera that followed it would swing the map to a heading that has nothing to do
 * with the road — the reported skew. This object detects that case ([isCourse
 * Trustworthy]) and, instead of chasing a bad course, HOLDS a known-good
 * road-aligned heading ([fallbackBearingDeg]) so the map keeps facing the way the
 * driver was last actually going.
 */
object NavFollowBearing {
    /**
     * Ground speed (m/s) below which a fix's course is not trusted — roughly
     * 5 km/h.
     *
     * Chosen at a slow walking pace: fast enough that a real GPS course has
     * settled into the direction of travel, slow enough that it does not suppress
     * course-up through ordinary slow driving (a tight junction, heavy traffic).
     * Below it the map holds its last road-aligned heading rather than chasing a
     * course the fix cannot actually resolve.
     */
    const val MIN_COURSE_SPEED_MPS: Double = 1.4

    /**
     * Whether a fix's [bearingDeg] can be trusted as the direction of travel,
     * given the fix's ground [speedMps].
     *
     * Both must be present and finite, and the speed must clear
     * [MIN_COURSE_SPEED_MPS] — a course reported while effectively stationary is
     * not a heading, it is noise.
     */
    fun isCourseTrustworthy(bearingDeg: Double?, speedMps: Double?): Boolean =
        bearingDeg != null && bearingDeg.isFinite() &&
            speedMps != null && speedMps.isFinite() && speedMps >= MIN_COURSE_SPEED_MPS

    /**
     * Normalise any angle in degrees to the `[0, 360)` range a camera bearing
     * expects; a non-finite input collapses to 0 (north).
     */
    fun normalizeDeg(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        val m = deg % 360.0
        return if (m < 0.0) m + 360.0 else m
    }

    /**
     * The bearing OVERRIDE to push at the follow camera's viewport data source
     * for the current [mode] and latest fix.
     *
     * The return is deliberately a nullable "override", matching the SDK's
     * `followingBearingPropertyOverride` contract:
     * - `null` HANDS the bearing back to the SDK's own course-up following, which
     *   blends the live location course with the route geometry. This is the
     *   moving [MapCompassMode.CourseUp] case — the SDK already does the right
     *   thing, so we do not fight it.
     * - a value PINS the following bearing:
     *   - [MapCompassMode.NorthUp] pins 0.0 (true north stays up while the camera
     *     goes on tracking the car along the route);
     *   - low-speed [MapCompassMode.CourseUp] pins [fallbackBearingDeg], the last
     *     road-aligned heading, so the map holds course-up instead of swinging to
     *     a course the stationary fix cannot resolve. With no fallback yet (the
     *     trip has not moved at all) it returns `null`, leaving the SDK its
     *     default — there is simply no better answer to hold to.
     *
     * @param gpsBearingDeg the latest fix's course, or null when the fix carries
     *   none.
     * @param speedMps the latest fix's ground speed, or null when unknown.
     * @param fallbackBearingDeg the last course seen while [isCourseTrustworthy]
     *   was true — a road-aligned heading to hold when the live course is not
     *   trustworthy. Null until the first trustworthy course of the trip.
     */
    fun followingBearingOverride(
        mode: MapCompassMode,
        gpsBearingDeg: Double?,
        speedMps: Double?,
        fallbackBearingDeg: Double?,
    ): Double? =
        when (mode) {
            MapCompassMode.NorthUp -> 0.0
            MapCompassMode.CourseUp ->
                if (isCourseTrustworthy(gpsBearingDeg, speedMps)) {
                    // Moving: the SDK's own following bearing (course blended with
                    // the route) is exactly what we want — hand it back.
                    null
                } else {
                    // Low speed / no course: hold the last road-aligned heading
                    // rather than chase a course the fix cannot resolve.
                    fallbackBearingDeg?.takeIf { it.isFinite() }?.let { normalizeDeg(it) }
                }
        }
}
