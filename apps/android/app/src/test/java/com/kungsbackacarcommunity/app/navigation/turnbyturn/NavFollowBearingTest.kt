package com.kungsbackacarcommunity.app.navigation.turnbyturn

import com.kungsbackacarcommunity.app.shell.MapCompassMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow camera's bearing decision behind "the map is not oriented to the
 * direction of travel while I drive".
 *
 * This is the only part of that fix that CAN be tested off-device: the camera it
 * feeds lives in `src/nav`, which needs the Mapbox Navigation SDK and a downloads
 * token and is therefore only ever compiled by CI. The bearing-SOURCE arithmetic
 * is pinned here.
 */
class NavFollowBearingTest {
    // ── isCourseTrustworthy ─────────────────────────────────────────────────

    @Test
    fun aMovingFixWithACourseIsTrustworthy() {
        assertTrue(NavFollowBearing.isCourseTrustworthy(bearingDeg = 90.0, speedMps = 15.0))
    }

    @Test
    fun aStationaryFixIsNotTrustworthyEvenWithACourse() {
        // The classic skew source: a course reported while effectively stopped is
        // noise, not a heading.
        assertFalse(NavFollowBearing.isCourseTrustworthy(bearingDeg = 90.0, speedMps = 0.0))
    }

    @Test
    fun aFixBelowTheSpeedFloorIsNotTrustworthy() {
        assertFalse(
            NavFollowBearing.isCourseTrustworthy(
                bearingDeg = 90.0,
                speedMps = NavFollowBearing.MIN_COURSE_SPEED_MPS - 0.1,
            ),
        )
    }

    @Test
    fun aFixAtExactlyTheSpeedFloorIsTrustworthy() {
        assertTrue(
            NavFollowBearing.isCourseTrustworthy(
                bearingDeg = 90.0,
                speedMps = NavFollowBearing.MIN_COURSE_SPEED_MPS,
            ),
        )
    }

    @Test
    fun aMissingOrNonFiniteCourseOrSpeedIsNotTrustworthy() {
        assertFalse(NavFollowBearing.isCourseTrustworthy(bearingDeg = null, speedMps = 15.0))
        assertFalse(NavFollowBearing.isCourseTrustworthy(bearingDeg = 90.0, speedMps = null))
        assertFalse(NavFollowBearing.isCourseTrustworthy(bearingDeg = Double.NaN, speedMps = 15.0))
        assertFalse(
            NavFollowBearing.isCourseTrustworthy(bearingDeg = 90.0, speedMps = Double.POSITIVE_INFINITY),
        )
    }

    // ── normalizeDeg ────────────────────────────────────────────────────────

    @Test
    fun normalizeWrapsIntoZeroToThreeSixty() {
        assertEquals(10.0, NavFollowBearing.normalizeDeg(370.0), 1e-9)
        assertEquals(350.0, NavFollowBearing.normalizeDeg(-10.0), 1e-9)
        assertEquals(0.0, NavFollowBearing.normalizeDeg(360.0), 1e-9)
        assertEquals(180.0, NavFollowBearing.normalizeDeg(180.0), 1e-9)
        // Non-finite collapses to north rather than propagating into a camera.
        assertEquals(0.0, NavFollowBearing.normalizeDeg(Double.NaN), 1e-9)
    }

    // ── followingBearingOverride ────────────────────────────────────────────

    @Test
    fun northUpAlwaysPinsZeroRegardlessOfTheFix() {
        // North-up ignores the course entirely: true north stays up.
        assertEquals(
            0.0,
            NavFollowBearing.followingBearingOverride(
                mode = MapCompassMode.NorthUp,
                gpsBearingDeg = 123.0,
                speedMps = 20.0,
                fallbackBearingDeg = 45.0,
            ),
        )
    }

    @Test
    fun courseUpWhileMovingHandsTheBearingToTheSdk() {
        // The whole point of "prefer the SDK's own following mechanism": a moving
        // course-up fix returns null, i.e. no override, letting the SDK blend the
        // location course with the route geometry.
        assertNull(
            NavFollowBearing.followingBearingOverride(
                mode = MapCompassMode.CourseUp,
                gpsBearingDeg = 90.0,
                speedMps = 15.0,
                fallbackBearingDeg = 45.0,
            ),
        )
    }

    @Test
    fun courseUpAtLowSpeedHoldsTheLastRoadAlignedHeading() {
        // **The regression test for the reported skew.** Stopped at a light with a
        // stale/absent course, the map must NOT swing to the bad course; it holds
        // the last trustworthy heading instead.
        assertEquals(
            45.0,
            NavFollowBearing.followingBearingOverride(
                mode = MapCompassMode.CourseUp,
                gpsBearingDeg = 270.0, // present but meaningless at 0 speed
                speedMps = 0.0,
                fallbackBearingDeg = 45.0,
            ),
        )
    }

    @Test
    fun courseUpAtLowSpeedNormalisesTheHeldHeading() {
        assertEquals(
            10.0,
            NavFollowBearing.followingBearingOverride(
                mode = MapCompassMode.CourseUp,
                gpsBearingDeg = null,
                speedMps = 0.0,
                fallbackBearingDeg = 370.0,
            ),
        )
    }

    @Test
    fun courseUpAtLowSpeedWithNoFallbackYetLeavesTheSdkDefault() {
        // Before the trip has ever moved there is no road-aligned heading to hold,
        // so there is nothing better than the SDK's own default.
        assertNull(
            NavFollowBearing.followingBearingOverride(
                mode = MapCompassMode.CourseUp,
                gpsBearingDeg = null,
                speedMps = 0.0,
                fallbackBearingDeg = null,
            ),
        )
    }
}
