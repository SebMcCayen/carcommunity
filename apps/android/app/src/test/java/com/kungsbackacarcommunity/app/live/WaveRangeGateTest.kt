package com.kungsbackacarcommunity.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the per-target, range-based anti-spam rule for the wave control: once you
 * wave a nearby driver you may NOT wave them again while they stay in range; only
 * leaving and re-entering range re-enables it. The 45 s server cooldown is a
 * separate time backstop — this guards the client UX gate that replaces the old
 * "bare timer lets you re-wave the same in-range person" behaviour.
 */
class WaveRangeGateTest {
    @Test
    fun `a driver not yet waved this visit is waveable`() {
        val gate = WaveRangeGate()
        gate.onRangeSet(listOf("a", "b"))
        assertTrue(gate.canWave("a"))
        assertTrue(gate.canWave("b"))
    }

    @Test
    fun `waving a driver blocks re-waving them while they stay in range`() {
        val gate = WaveRangeGate()
        gate.onRangeSet(listOf("a"))
        gate.onWaved("a")
        assertFalse(gate.canWave("a"))

        // Still in range on a later roster refresh: still blocked.
        gate.onRangeSet(listOf("a"))
        assertFalse(gate.canWave("a"))
    }

    @Test
    fun `a driver leaving range then returning is waveable again`() {
        val gate = WaveRangeGate()
        gate.onRangeSet(listOf("a"))
        gate.onWaved("a")
        assertFalse(gate.canWave("a"))

        // They leave wave range (drop out of the in-range set): mark cleared.
        gate.onRangeSet(emptyList())
        assertTrue(gate.canWave("a"))

        // Coming back INTO range re-offers the wave.
        gate.onRangeSet(listOf("a"))
        assertTrue(gate.canWave("a"))
    }

    @Test
    fun `targets are tracked independently`() {
        val gate = WaveRangeGate()
        gate.onRangeSet(listOf("a", "b"))
        gate.onWaved("a")
        assertFalse(gate.canWave("a"))
        // B was never waved — still waveable.
        assertTrue(gate.canWave("b"))
    }

    @Test
    fun `the broadcast overload marks every in-range driver waved at once`() {
        val gate = WaveRangeGate()
        gate.onRangeSet(listOf("a", "b", "c"))
        gate.onWaved(listOf("a", "b", "c"))
        assertFalse(gate.canWave("a"))
        assertFalse(gate.canWave("b"))
        assertFalse(gate.canWave("c"))
    }

    @Test
    fun `a driver who joins range after a broadcast is still waveable`() {
        val gate = WaveRangeGate()
        gate.onRangeSet(listOf("a"))
        gate.onWaved(listOf("a"))
        assertFalse(gate.canWave("a"))

        // A new driver appears; the broadcast that waved A never touched them.
        gate.onRangeSet(listOf("a", "b"))
        assertFalse(gate.canWave("a"))
        assertTrue(gate.canWave("b"))
    }

    @Test
    fun `waveableCount counts only in-range drivers not yet waved`() {
        val gate = WaveRangeGate()
        val inRange = listOf("a", "b", "c")
        gate.onRangeSet(inRange)
        assertEquals(3, gate.waveableCount(inRange))

        gate.onWaved(inRange)
        assertEquals(0, gate.waveableCount(inRange))

        // A fresh driver enters range: exactly one is waveable again.
        val grown = listOf("a", "b", "c", "d")
        gate.onRangeSet(grown)
        assertEquals(1, gate.waveableCount(grown))
    }

    @Test
    fun `a failed send leaves the driver waveable (gate committed only on success)`() {
        // Mirrors the AuthenticatedApp contract: onWaved is called ONLY after a
        // successful send. A RateLimited/Failed/NotSharing reply never calls
        // onWaved, so the snapshot of in-range drivers stays fully waveable and the
        // control is not wrongly hidden for people who were never actually waved.
        val gate = WaveRangeGate()
        val inRange = listOf("a", "b")
        gate.onRangeSet(inRange)

        // Server rejected the send -> onWaved NOT invoked.
        assertTrue(gate.canWave("a"))
        assertTrue(gate.canWave("b"))
        assertEquals(2, gate.waveableCount(inRange))

        // A later SUCCESSFUL send does commit the snapshot.
        gate.onWaved(inRange)
        assertEquals(0, gate.waveableCount(inRange))
    }

    @Test
    fun `re-waving after a driver cycles out and back in is allowed`() {
        val gate = WaveRangeGate()
        gate.onRangeSet(listOf("a"))
        gate.onWaved("a")

        // Cycle: out of range, then back.
        gate.onRangeSet(emptyList())
        gate.onRangeSet(listOf("a"))
        assertEquals(1, gate.waveableCount(listOf("a")))

        gate.onWaved("a")
        assertEquals(0, gate.waveableCount(listOf("a")))
    }
}
