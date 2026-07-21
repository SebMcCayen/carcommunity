package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the pure [drivesLevel] routing decision used by [DrivesRoute]. */
class DrivesRouteTest {

    @Test
    fun `a resolved selected drive always wins`() {
        assertEquals(
            DrivesLevel.DETAIL,
            drivesLevel(hasSelectedDrive = true, showStats = true, isLoaded = true),
        )
    }

    @Test
    fun `stats renders when open and the list is loaded`() {
        assertEquals(
            DrivesLevel.STATS,
            drivesLevel(hasSelectedDrive = false, showStats = true, isLoaded = true),
        )
    }

    @Test
    fun `stats open but list not loaded falls back to the list, not an empty stats page`() {
        // Reproduces the transient-state bug: the drives left Loaded (a listener
        // error / resubscribe) while stats was open. The route must show the list
        // (which renders the real loading/error state), never an empty fold.
        assertEquals(
            DrivesLevel.LIST,
            drivesLevel(hasSelectedDrive = false, showStats = true, isLoaded = false),
        )
    }

    @Test
    fun `nothing open resolves to the list`() {
        assertEquals(
            DrivesLevel.LIST,
            drivesLevel(hasSelectedDrive = false, showStats = false, isLoaded = true),
        )
    }
}
