package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The seam the compass regression test leans on.
 *
 * `MapFirstShellTest.compassControl_togglesOrientation_andRecentresEachTap`
 * asserts that tapping the compass bumps [StubMapSurface.recenterCount]. That
 * assertion is only worth anything if a bearing-only wiring would leave that
 * counter at zero, and instrumentation tests are `continue-on-error` in CI so a
 * silent rot there would not turn the build red. (The compass is now a two-mode
 * orientation toggle; see [CompassModeSeamTest] for that seam.)
 *
 * These run in the BLOCKING unit-test job and pin the discriminating property
 * directly: the two calls are distinguishable, and `recenterNorthUp` is the only
 * one of them that counts as a re-centre.
 */
class CompassRecenterSeamTest {
    /**
     * The old compass behaviour. If this ever starts incrementing
     * `recenterCount`, the shell test above stops being a regression test and
     * would pass against the bug it exists to catch.
     */
    @Test
    fun resetNorthAloneDoesNotCountAsARecentre() {
        val surface = StubMapSurface()
        surface.resetNorth()
        assertEquals(0, surface.recenterCount)
        assertEquals(1, surface.resetNorthCount)
    }

    /** The fixed compass behaviour: north-up AND a re-centre, from one call. */
    @Test
    fun recenterNorthUpCountsAsBothARecentreAndANorthReset() {
        val surface = StubMapSurface()
        surface.recenterNorthUp()
        assertEquals(1, surface.recenterCount)
        assertEquals(1, surface.resetNorthCount)
    }

    /**
     * The my-location control is unchanged: it re-centres WITHOUT touching
     * bearing, which is what still distinguishes it from the compass.
     */
    @Test
    fun plainRecentreLeavesTheBearingAlone() {
        val surface = StubMapSurface()
        surface.recenter()
        assertEquals(1, surface.recenterCount)
        assertEquals(0, surface.resetNorthCount)
    }
}
