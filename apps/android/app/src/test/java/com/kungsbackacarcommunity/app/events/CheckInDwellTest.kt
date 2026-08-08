package com.kungsbackacarcommunity.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CheckInDwell] — the pure dwell-countdown maths: remaining time,
 * progress fraction, the dwell-elapsed boundary ("can I confirm yet?"), and the
 * m:ss rounding. Pure JVM, no Android/Firebase.
 */
class CheckInDwellTest {

    private val required = CheckInDwell.REQUIRED_DWELL_MS
    private val first = 1_000_000_000_000L

    @Test
    fun `remaining is the whole dwell at the first sample and zero once elapsed`() {
        assertEquals(required, CheckInDwell.remainingMillis(first, first))
        assertEquals(required / 2, CheckInDwell.remainingMillis(first, first + required / 2))
        assertEquals(0L, CheckInDwell.remainingMillis(first, first + required))
        assertEquals(0L, CheckInDwell.remainingMillis(first, first + required + 60_000L))
    }

    @Test
    fun `remaining is clamped when the clock reads before the first sample`() {
        // A backwards clock (NTP correction) must never show MORE than the dwell.
        assertEquals(required, CheckInDwell.remainingMillis(first, first - 5_000L))
    }

    @Test
    fun `progress runs 0 to 1 and clamps at both ends`() {
        assertEquals(0f, CheckInDwell.progressFraction(first, first), 0.0001f)
        assertEquals(0.5f, CheckInDwell.progressFraction(first, first + required / 2), 0.0001f)
        assertEquals(1f, CheckInDwell.progressFraction(first, first + required), 0.0001f)
        assertEquals(1f, CheckInDwell.progressFraction(first, first + required * 2), 0.0001f)
        assertEquals(0f, CheckInDwell.progressFraction(first, first - 10_000L), 0.0001f)
    }

    @Test
    fun `dwell elapses exactly at the boundary`() {
        assertFalse(CheckInDwell.isDwellElapsed(first, first + required - 1))
        assertTrue(CheckInDwell.isDwellElapsed(first, first + required))
    }

    @Test
    fun `remaining minutes and seconds round up so 0-00 means genuinely zero`() {
        assertEquals(10 to 0, CheckInDwell.remainingMinutesSeconds(10L * 60_000L))
        assertEquals(9 to 59, CheckInDwell.remainingMinutesSeconds(9L * 60_000L + 59_000L))
        // A sub-second remainder still reads as 0:01, never a premature 0:00.
        assertEquals(0 to 1, CheckInDwell.remainingMinutesSeconds(1L))
        assertEquals(0 to 0, CheckInDwell.remainingMinutesSeconds(0L))
        assertEquals(1 to 1, CheckInDwell.remainingMinutesSeconds(61_000L))
    }

    @Test
    fun `required dwell matches the ten-minute server constant`() {
        assertEquals(10L * 60_000L, CheckInDwell.REQUIRED_DWELL_MS)
    }

    @Test
    fun `selectAnchor takes the earliest of the two references`() {
        // Snapshot lag: only the session fix is known yet.
        assertEquals(first, CheckInDwell.selectAnchor(first, null))
        // Process restart, before any new tap: only the persisted createdAt.
        assertEquals(first, CheckInDwell.selectAnchor(null, first))
        // Both known — the earlier wins, so the snapshot never moves the anchor.
        assertEquals(first, CheckInDwell.selectAnchor(first, first + 3_000L))
        // Restart then a LATER new-session tap must not push the anchor forward
        // past the persisted (earlier, correct) original.
        assertEquals(first, CheckInDwell.selectAnchor(first + 5 * 60_000L, first))
        // Neither known.
        assertEquals(null, CheckInDwell.selectAnchor(null, null))
    }
}
