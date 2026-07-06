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
    }

    @Test
    fun `duration fromKey round-trips and rejects unknown`() {
        assertEquals(LiveSessionDuration.TWO_HOURS, LiveSessionDuration.fromKey("2h"))
        assertNull(LiveSessionDuration.fromKey("3h"))
        assertNull(LiveSessionDuration.fromKey(null))
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
