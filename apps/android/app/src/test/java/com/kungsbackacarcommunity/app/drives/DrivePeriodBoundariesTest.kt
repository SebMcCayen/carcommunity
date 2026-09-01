package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The month range the client sends to `drives-stats` must satisfy the server's
 * strict validation (driveStats-core.resolveMonthRange): it must straddle server
 * time and span a single calendar month (27–32 days). This pins the client side
 * of that contract so a boundary bug can't push every stats call to
 * invalid-argument.
 */
class DrivePeriodBoundariesTest {

    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun `the current-month range straddles now and spans a single calendar month`() {
        val start = DrivePeriodBoundaries.startOfCurrentMonthMillis()
        val end = DrivePeriodBoundaries.startOfNextMonthMillis()
        val now = System.currentTimeMillis()

        // Straddles server time (start < now < end) — the server rejects otherwise.
        assertTrue("month start must be at or before now", start <= now)
        assertTrue("month end must be after now", end > now)

        // Spans 27–32 days: a real calendar month is 28–31 days, comfortably inside.
        val span = end - start
        assertTrue("span too short: $span", span >= 27 * dayMs)
        assertTrue("span too long: $span", span <= 32 * dayMs)
    }
}
