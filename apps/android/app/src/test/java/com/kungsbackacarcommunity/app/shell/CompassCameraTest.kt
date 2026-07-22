package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure bearing-decision behind the two-mode compass. The camera move itself
 * needs a real Mapbox surface (verified on device), but the RULE — what bearing
 * each mode faces — is pure and pinned here in the blocking unit-test job.
 */
class CompassCameraTest {
    @Test
    fun northUp_isAlwaysZero_ignoringHeading() {
        assertEquals(0.0, CompassCamera.followBearing(MapCompassMode.NorthUp, 0.0), 0.0)
        assertEquals(0.0, CompassCamera.followBearing(MapCompassMode.NorthUp, 137.0), 0.0)
        assertEquals(0.0, CompassCamera.followBearing(MapCompassMode.NorthUp, 359.9), 0.0)
    }

    @Test
    fun courseUp_facesTheHeading() {
        assertEquals(0.0, CompassCamera.followBearing(MapCompassMode.CourseUp, 0.0), 0.0)
        assertEquals(137.0, CompassCamera.followBearing(MapCompassMode.CourseUp, 137.0), 0.0)
        assertEquals(359.9, CompassCamera.followBearing(MapCompassMode.CourseUp, 359.9), 0.0)
    }
}
