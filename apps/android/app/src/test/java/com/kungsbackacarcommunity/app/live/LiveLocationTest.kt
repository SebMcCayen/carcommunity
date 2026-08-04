package com.kungsbackacarcommunity.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationTest {

    @Test
    fun `duration keys and hours mirror the backend`() {
        assertEquals("1h", LiveSessionDuration.ONE_HOUR.key)
        assertEquals(1, LiveSessionDuration.ONE_HOUR.hours)
        assertEquals("2h", LiveSessionDuration.TWO_HOURS.key)
        assertEquals(2, LiveSessionDuration.TWO_HOURS.hours)
        assertEquals("4h", LiveSessionDuration.FOUR_HOURS.key)
        assertEquals(4, LiveSessionDuration.FOUR_HOURS.hours)
        assertEquals("6h", LiveSessionDuration.SIX_HOURS.key)
        assertEquals(6, LiveSessionDuration.SIX_HOURS.hours)
    }

    @Test
    fun `duration fromKey round-trips and rejects unknown`() {
        assertEquals(LiveSessionDuration.TWO_HOURS, LiveSessionDuration.fromKey("2h"))
        assertEquals(LiveSessionDuration.SIX_HOURS, LiveSessionDuration.fromKey("6h"))
        assertNull(LiveSessionDuration.fromKey("3h"))
        assertNull(LiveSessionDuration.fromKey(null))
    }

    @Test
    fun `the default session duration is the 6h hard-cap window`() {
        // Every session (single and convoy) now runs the full 6h maximum and
        // auto-stops, with no prompt to prolong it.
        assertEquals(LiveSessionDuration.SIX_HOURS, LiveLocation.DEFAULT_SESSION_DURATION)
        assertEquals(6L * 60 * 60 * 1000, LiveLocation.LIVE_SESSION_MAX_MS)
        assertEquals(
            LiveLocation.DEFAULT_SESSION_DURATION.hours * 60L * 60 * 1000,
            LiveLocation.LIVE_SESSION_MAX_MS,
        )
    }

    @Test
    fun `status fromWire maps known values and rejects others`() {
        assertEquals(LiveSessionStatus.ACTIVE, LiveSessionStatus.fromWire("active"))
        assertEquals(LiveSessionStatus.STOPPED, LiveSessionStatus.fromWire("stopped"))
        assertEquals(LiveSessionStatus.EXPIRED, LiveSessionStatus.fromWire("expired"))
        assertNull(LiveSessionStatus.fromWire("paused"))
        assertNull(LiveSessionStatus.fromWire(null))
    }

    @Test
    fun `isSharing is true for an active unexpired session`() {
        val session = session(LiveSessionStatus.ACTIVE, expiresAtMillis = 2_000L)
        assertTrue(LiveLocation.isSharing(session, nowMillis = 1_000L))
    }

    @Test
    fun `isSharing is false once the session has expired`() {
        val session = session(LiveSessionStatus.ACTIVE, expiresAtMillis = 1_000L)
        assertFalse(LiveLocation.isSharing(session, nowMillis = 1_000L))
        assertFalse(LiveLocation.isSharing(session, nowMillis = 5_000L))
    }

    @Test
    fun `isSharing is false for a stopped session and for null`() {
        assertFalse(LiveLocation.isSharing(session(LiveSessionStatus.STOPPED, 9_999L), 1L))
        assertFalse(LiveLocation.isSharing(null, 1L))
    }

    @Test
    fun `isSharing trusts an active session with an unparseable expiry`() {
        val session = session(LiveSessionStatus.ACTIVE, expiresAtMillis = null)
        assertTrue(LiveLocation.isSharing(session, nowMillis = 1_000L))
    }

    @Test
    fun `session cap constant matches the server`() {
        // The Kotlin copy MUST equal the server's LIVE_SESSION_MAX_MS (6h) — see
        // functions/src/live/live-core.ts and its live-core.test.ts. Retune BOTH
        // sides together.
        assertEquals(6 * 60 * 60 * 1000L, LiveLocation.LIVE_SESSION_MAX_MS)
    }

    @Test
    fun `remainingSeconds floors at zero and returns null when unknown`() {
        assertEquals(5L, LiveLocation.remainingSeconds(session(LiveSessionStatus.ACTIVE, 6_000L), 1_000L))
        assertEquals(0L, LiveLocation.remainingSeconds(session(LiveSessionStatus.ACTIVE, 1_000L), 5_000L))
        assertNull(LiveLocation.remainingSeconds(session(LiveSessionStatus.ACTIVE, null), 1_000L))
        assertNull(LiveLocation.remainingSeconds(null, 1_000L))
    }

    private fun session(status: LiveSessionStatus, expiresAtMillis: Long?) =
        LiveSessionInfo(
            sessionId = "s1",
            status = status,
            duration = LiveSessionDuration.ONE_HOUR,
            expiresAtMillis = expiresAtMillis,
        )
}
