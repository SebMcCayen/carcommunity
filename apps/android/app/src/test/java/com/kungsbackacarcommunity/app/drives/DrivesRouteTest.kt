package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the pure [drivesLevel] routing decision used by [DrivesRoute]. */
class DrivesRouteTest {

    @Test
    fun `a resolved selected drive resolves to the detail level`() {
        assertEquals(
            DrivesLevel.DETAIL,
            drivesLevel(hasSelectedDrive = true),
        )
    }

    @Test
    fun `nothing open resolves to the list`() {
        assertEquals(
            DrivesLevel.LIST,
            drivesLevel(hasSelectedDrive = false),
        )
    }
}
