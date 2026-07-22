package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stub seam the two-mode compass leans on, pinned in the BLOCKING unit-test
 * job (the shell/icon assertions in `MapFirstShellTest` are instrumentation, which
 * is `continue-on-error` in CI, so the wiring contract is asserted here too).
 *
 * [StubMapSurface.setCompassMode] mirrors the real surface: a genuine mode change
 * re-centres on the user (like the my-location control), and switching to
 * north-up also resets north — while re-pushing the SAME mode is a no-op, which is
 * what lets the shell re-sync the saved mode on a surface swap without forcing a
 * camera move on open.
 */
class CompassModeSeamTest {
    @Test
    fun defaultsToNorthUp() {
        val surface = StubMapSurface()
        assertEquals(MapCompassMode.NorthUp, surface.compassMode)
        assertEquals(0, surface.compassModeChanges)
    }

    @Test
    fun switchingToCourseUp_recentresButDoesNotResetNorth() {
        val surface = StubMapSurface()
        surface.setCompassMode(MapCompassMode.CourseUp)
        assertEquals(MapCompassMode.CourseUp, surface.compassMode)
        assertEquals(1, surface.compassModeChanges)
        assertEquals("course-up still re-centres", 1, surface.recenterCount)
        assertEquals("course-up does not reset north", 0, surface.resetNorthCount)
    }

    @Test
    fun returningToNorthUp_recentresAndResetsNorth() {
        val surface = StubMapSurface()
        surface.setCompassMode(MapCompassMode.CourseUp)
        surface.setCompassMode(MapCompassMode.NorthUp)
        assertEquals(MapCompassMode.NorthUp, surface.compassMode)
        assertEquals(2, surface.compassModeChanges)
        assertEquals(2, surface.recenterCount)
        assertEquals("returning to north-up resets north", 1, surface.resetNorthCount)
    }

    @Test
    fun rePushingTheSameMode_isANoOp() {
        val surface = StubMapSurface()
        // The shell re-pushes the saved mode on a surface swap; an unchanged mode
        // must not move the camera (no spurious re-centre on open).
        surface.setCompassMode(MapCompassMode.NorthUp)
        assertEquals(0, surface.compassModeChanges)
        assertEquals(0, surface.recenterCount)
        assertEquals(0, surface.resetNorthCount)
    }
}
