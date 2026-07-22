package com.kungsbackacarcommunity.app.shell

/**
 * The pure "what bearing should the camera take?" decision behind the two-mode
 * compass, extracted so it can be unit-tested off-device (the camera move itself
 * needs a real Mapbox surface and is verified on device).
 *
 * The rule is deliberately tiny and total: in [MapCompassMode.NorthUp] the map is
 * pinned to true north (0°); in [MapCompassMode.CourseUp] it faces the user's
 * heading. It is the single source of truth for the bearing the follow path
 * applies while tracking the user in each mode, so north-up and course-up can
 * never drift apart in the several places the surface sets a camera bearing.
 */
object CompassCamera {
    /**
     * The camera bearing (degrees, 0 = north-up, clockwise) to apply while
     * following the user in [mode], given the puck's current [heading].
     *
     * - [MapCompassMode.NorthUp] → 0.0 (north stays up), ignoring [heading].
     * - [MapCompassMode.CourseUp] → [heading] (the map rotates to the direction
     *   of travel).
     */
    fun followBearing(mode: MapCompassMode, heading: Double): Double =
        when (mode) {
            MapCompassMode.NorthUp -> 0.0
            MapCompassMode.CourseUp -> heading
        }
}
