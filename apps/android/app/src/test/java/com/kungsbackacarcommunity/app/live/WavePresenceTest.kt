package com.kungsbackacarcommunity.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure client decisions for the wave-to-nearby control: WHEN the icon is
 * shown (sharing + someone nearby), and the client cooldown gate that greys it.
 * The server is the real anti-spam authority; these guard the UX mirror.
 */
class WavePresenceTest {
    // --- visibility ---------------------------------------------------------

    @Test
    fun `wave control is shown only while sharing AND someone is nearby`() {
        assertTrue(WavePresence.isWaveControlVisible(isSharingLive = true, waveableInRangeCount = 1))
        assertTrue(WavePresence.isWaveControlVisible(isSharingLive = true, waveableInRangeCount = 7))
    }

    @Test
    fun `wave control is hidden when nobody is nearby`() {
        assertFalse(WavePresence.isWaveControlVisible(isSharingLive = true, waveableInRangeCount = 0))
    }

    @Test
    fun `wave control is hidden when not sharing, even with people nearby`() {
        assertFalse(WavePresence.isWaveControlVisible(isSharingLive = false, waveableInRangeCount = 3))
        assertFalse(WavePresence.isWaveControlVisible(isSharingLive = false, waveableInRangeCount = 0))
    }

    // --- cooldown gate ------------------------------------------------------

    @Test
    fun `send is enabled once now reaches the cooldown deadline`() {
        assertFalse(WavePresence.isSendEnabled(nowMs = 1_000, cooldownUntilMs = 5_000))
        // Exactly at the deadline is enabled (inclusive).
        assertTrue(WavePresence.isSendEnabled(nowMs = 5_000, cooldownUntilMs = 5_000))
        assertTrue(WavePresence.isSendEnabled(nowMs = 6_000, cooldownUntilMs = 5_000))
    }

    @Test
    fun `a zero deadline (never waved) is always enabled`() {
        assertTrue(WavePresence.isSendEnabled(nowMs = 0, cooldownUntilMs = 0))
    }

    @Test
    fun `cooldownUntil adds the window to now and mirrors the server default`() {
        assertEquals(1_045_000L, WavePresence.cooldownUntil(nowMs = 1_000_000L))
        assertEquals(WAVE_COOLDOWN_MS, WavePresence.cooldownUntil(nowMs = 0L))
        assertEquals(5_500L, WavePresence.cooldownUntil(nowMs = 500L, windowMs = 5_000L))
    }
}
