package com.kungsbackacarcommunity.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the render watchdog — the check that would actually have caught the
 * v0.8.1 blank map, and therefore the one most capable of doing harm if it
 * misfires.
 *
 * The watchdog is clock-free by design (the caller supplies elapsed time), so
 * every benign path that could otherwise trip it is exercised here as ordinary
 * arithmetic rather than as a flaky timing test.
 */
class MapRenderWatchdogTest {

    private val tick = 1_000L

    /** Drive [ticks] ticks and return how many times the watchdog fired. */
    private fun run(
        watchdog: MapRenderWatchdog,
        ticks: Int,
        eligible: Boolean = true,
        rendered: Boolean = false,
    ): Int = (1..ticks).count { watchdog.onTick(tick, eligible, rendered) }

    @Test
    fun `fires once after the timeout of eligible time`() {
        val watchdog = MapRenderWatchdog(timeoutMillis = 12_000L)
        assertEquals(0, run(watchdog, ticks = 11))
        assertEquals(1, run(watchdog, ticks = 1))
    }

    @Test
    fun `never fires twice`() {
        val watchdog = MapRenderWatchdog(timeoutMillis = 3_000L)
        assertEquals(1, run(watchdog, ticks = 50))
        assertTrue(watchdog.isDisarmed)
    }

    @Test
    fun `never fires once the map has rendered`() {
        val watchdog = MapRenderWatchdog(timeoutMillis = 3_000L)
        run(watchdog, ticks = 2)
        // The map renders on tick 3, just before the budget would have expired.
        assertEquals(0, run(watchdog, ticks = 1, rendered = true))
        // And it can never fire afterwards, even if the rendered signal is lost.
        assertEquals(0, run(watchdog, ticks = 100))
        assertTrue(watchdog.isDisarmed)
    }

    @Test
    fun `a map that rendered and later went blank is not a render timeout`() {
        // That is a different defect; firing MAP_RENDER_TIMEOUT for it would
        // mislabel the issue and merge two distinct faults.
        val watchdog = MapRenderWatchdog(timeoutMillis = 5_000L)
        run(watchdog, ticks = 1, rendered = true)
        assertEquals(0, run(watchdog, ticks = 100, rendered = false))
    }

    // ---- The benign paths that must NOT trip it -------------------------------

    @Test
    fun `time spent offline never accrues`() {
        // The tunnel case: a user underground for ten minutes with a blank map.
        val watchdog = MapRenderWatchdog(timeoutMillis = 12_000L)
        assertEquals(0, run(watchdog, ticks = 600, eligible = false))
        assertEquals(0L, watchdog.elapsedMillis)
        assertFalse(watchdog.isDisarmed)
    }

    @Test
    fun `an ineligible stretch pauses rather than resets the budget`() {
        // A user who briefly loses signal mid-load should still be protected from
        // a false positive, but a genuinely broken map must not become
        // unreportable just because connectivity flickered once.
        val watchdog = MapRenderWatchdog(timeoutMillis = 12_000L)
        run(watchdog, ticks = 6)
        assertEquals(6_000L, watchdog.elapsedMillis)
        run(watchdog, ticks = 300, eligible = false)
        assertEquals(6_000L, watchdog.elapsedMillis)
        assertEquals(0, run(watchdog, ticks = 5))
        assertEquals(1, run(watchdog, ticks = 1))
    }

    @Test
    fun `interleaved eligible and ineligible ticks only count the eligible ones`() {
        val watchdog = MapRenderWatchdog(timeoutMillis = 4_000L)
        var fires = 0
        // 8 ticks alternating; only 4 are eligible, so it fires on the last one.
        repeat(8) { index ->
            if (watchdog.onTick(tick, eligible = index % 2 == 0, rendered = false)) fires++
        }
        assertEquals(1, fires)
        assertEquals(4_000L, watchdog.elapsedMillis)
    }

    @Test
    fun `a zero or negative tick cannot advance the budget`() {
        val watchdog = MapRenderWatchdog(timeoutMillis = 1_000L)
        assertFalse(watchdog.onTick(0L, eligible = true, rendered = false))
        assertFalse(watchdog.onTick(-5_000L, eligible = true, rendered = false))
        assertEquals(0L, watchdog.elapsedMillis)
    }

    @Test
    fun `the default budget is the documented twelve seconds`() {
        val watchdog = MapRenderWatchdog()
        assertEquals(12_000L, MAP_RENDER_TIMEOUT_MILLIS)
        assertEquals(0, run(watchdog, ticks = 11))
        assertEquals(1, run(watchdog, ticks = 1))
    }
}
