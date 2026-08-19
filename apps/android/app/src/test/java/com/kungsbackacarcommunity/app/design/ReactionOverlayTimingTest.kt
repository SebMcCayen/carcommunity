package com.kungsbackacarcommunity.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the reaction-overlay state machine: the show -> hold -> hide phase
 * boundaries, the total (short) visible duration, the fade, and the finished
 * signal. Pure — no Compose, no device.
 */
class ReactionOverlayTimingTest {

    @Test
    fun total_is_the_sum_of_the_three_phases_and_short() {
        assertEquals(
            ReactionOverlayTiming.ENTER_MS +
                ReactionOverlayTiming.HOLD_MS +
                ReactionOverlayTiming.EXIT_MS,
            ReactionOverlayTiming.TOTAL_MS,
        )
        // The pop must not linger over the map: well under two seconds.
        assertTrue(ReactionOverlayTiming.TOTAL_MS < 2_000L)
    }

    @Test
    fun phase_boundaries_are_half_open_in_order() {
        val enter = ReactionOverlayTiming.ENTER_MS
        val hold = ReactionOverlayTiming.HOLD_MS

        assertEquals(ReactionOverlayPhase.Hidden, ReactionOverlayTiming.phaseAt(-1))
        assertEquals(ReactionOverlayPhase.Entering, ReactionOverlayTiming.phaseAt(0))
        assertEquals(ReactionOverlayPhase.Entering, ReactionOverlayTiming.phaseAt(enter - 1))
        // At exactly ENTER_MS the enter is done and the hold begins.
        assertEquals(ReactionOverlayPhase.Holding, ReactionOverlayTiming.phaseAt(enter))
        assertEquals(ReactionOverlayPhase.Holding, ReactionOverlayTiming.phaseAt(enter + hold - 1))
        // At ENTER+HOLD the exit begins.
        assertEquals(ReactionOverlayPhase.Exiting, ReactionOverlayTiming.phaseAt(enter + hold))
        assertEquals(
            ReactionOverlayPhase.Exiting,
            ReactionOverlayTiming.phaseAt(ReactionOverlayTiming.TOTAL_MS - 1),
        )
        // At TOTAL and beyond it is hidden again.
        assertEquals(
            ReactionOverlayPhase.Hidden,
            ReactionOverlayTiming.phaseAt(ReactionOverlayTiming.TOTAL_MS),
        )
        assertEquals(
            ReactionOverlayPhase.Hidden,
            ReactionOverlayTiming.phaseAt(ReactionOverlayTiming.TOTAL_MS + 500),
        )
    }

    @Test
    fun isFinished_only_at_or_after_total() {
        assertFalse(ReactionOverlayTiming.isFinished(0))
        assertFalse(ReactionOverlayTiming.isFinished(ReactionOverlayTiming.TOTAL_MS - 1))
        assertTrue(ReactionOverlayTiming.isFinished(ReactionOverlayTiming.TOTAL_MS))
        assertTrue(ReactionOverlayTiming.isFinished(ReactionOverlayTiming.TOTAL_MS + 10))
    }

    @Test
    fun alpha_fades_in_holds_solid_then_fades_out_and_is_zero_outside() {
        val enter = ReactionOverlayTiming.ENTER_MS
        val hold = ReactionOverlayTiming.HOLD_MS

        assertEquals(0f, ReactionOverlayTiming.alphaAt(-1), 0f)
        assertEquals(0f, ReactionOverlayTiming.alphaAt(0), 0.001f)
        // Half-way through the enter → ~0.5 alpha.
        assertEquals(0.5f, ReactionOverlayTiming.alphaAt(enter / 2), 0.02f)
        // Solid through the hold.
        assertEquals(1f, ReactionOverlayTiming.alphaAt(enter), 0f)
        assertEquals(1f, ReactionOverlayTiming.alphaAt(enter + hold - 1), 0f)
        // Half-way through the exit → ~0.5 alpha.
        assertEquals(
            0.5f,
            ReactionOverlayTiming.alphaAt(enter + hold + ReactionOverlayTiming.EXIT_MS / 2),
            0.02f,
        )
        // Fully faded at/after the end.
        assertEquals(0f, ReactionOverlayTiming.alphaAt(ReactionOverlayTiming.TOTAL_MS), 0f)
    }

    @Test
    fun alpha_is_always_within_unit_range() {
        var t = -50L
        while (t <= ReactionOverlayTiming.TOTAL_MS + 50) {
            val a = ReactionOverlayTiming.alphaAt(t)
            assertTrue("alpha out of range at $t: $a", a in 0f..1f)
            t += 17
        }
    }
}
