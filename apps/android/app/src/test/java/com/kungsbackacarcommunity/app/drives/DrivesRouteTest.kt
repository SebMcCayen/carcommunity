package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the pure [drivesLevel] routing decision used by [DrivesRoute]. */
class DrivesRouteTest {

    @Test
    fun `a resolved selected drive always wins`() {
        assertEquals(
            DrivesLevel.DETAIL,
            drivesLevel(hasSelectedDrive = true, showStats = true),
        )
    }

    @Test
    fun `stats renders when open`() {
        // Statistics is now server-authoritative (its own callable, not a fold over
        // the list), so it no longer depends on the history list being loaded and
        // stays open across a history reload.
        assertEquals(
            DrivesLevel.STATS,
            drivesLevel(hasSelectedDrive = false, showStats = true),
        )
    }

    @Test
    fun `nothing open resolves to the list`() {
        assertEquals(
            DrivesLevel.LIST,
            drivesLevel(hasSelectedDrive = false, showStats = false),
        )
    }
}
