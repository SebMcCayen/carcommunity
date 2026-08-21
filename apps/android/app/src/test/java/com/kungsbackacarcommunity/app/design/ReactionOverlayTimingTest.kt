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

    // ---- Per-pop hold override (the ~5s "Police nearby" alert) ----------------

    @Test
    fun default_hold_arg_matches_the_constant_overloads() {
        // The no-arg overloads must be exactly the holdMs=HOLD_MS case, so social
        // pops are unchanged by the parameterisation.
        val hold = ReactionOverlayTiming.HOLD_MS
        assertEquals(ReactionOverlayTiming.TOTAL_MS, ReactionOverlayTiming.totalMs(hold))
        for (t in longArrayOf(-1, 0, 100, hold + ReactionOverlayTiming.ENTER_MS, ReactionOverlayTiming.TOTAL_MS)) {
            assertEquals(ReactionOverlayTiming.phaseAt(t), ReactionOverlayTiming.phaseAt(t, hold))
            assertEquals(ReactionOverlayTiming.alphaAt(t), ReactionOverlayTiming.alphaAt(t, hold), 0f)
        }
    }

    @Test
    fun a_longer_hold_stretches_the_holding_phase_and_total_only() {
        val enter = ReactionOverlayTiming.ENTER_MS
        val exit = ReactionOverlayTiming.EXIT_MS
        val longHold = 5_000L

        assertEquals(enter + longHold + exit, ReactionOverlayTiming.totalMs(longHold))

        // Still holding well past where the default (1.1s) pop would have exited.
        assertEquals(
            ReactionOverlayPhase.Holding,
            ReactionOverlayTiming.phaseAt(enter + ReactionOverlayTiming.HOLD_MS + 500, longHold),
        )
        assertEquals(1f, ReactionOverlayTiming.alphaAt(enter + longHold - 1, longHold), 0f)
        // Exit begins only at enter + longHold.
        assertEquals(
            ReactionOverlayPhase.Exiting,
            ReactionOverlayTiming.phaseAt(enter + longHold, longHold),
        )
        assertFalse(ReactionOverlayTiming.isFinished(ReactionOverlayTiming.totalMs(longHold) - 1, longHold))
        assertTrue(ReactionOverlayTiming.isFinished(ReactionOverlayTiming.totalMs(longHold), longHold))
    }

    @Test
    fun alpha_stays_in_unit_range_for_a_long_hold() {
        val longHold = 5_000L
        var t = -50L
        val total = ReactionOverlayTiming.totalMs(longHold)
        while (t <= total + 50) {
            val a = ReactionOverlayTiming.alphaAt(t, longHold)
            assertTrue("alpha out of range at $t: $a", a in 0f..1f)
            t += 37
        }
    }

    // ---- Wave rock (opt-in "waving hello" tilt) -------------------------------

    @Test
    fun wave_rock_is_zero_outside_the_hold() {
        val enter = ReactionOverlayTiming.ENTER_MS
        val hold = ReactionOverlayTiming.HOLD_MS
        // No tilt during the enter (the scale/settle-spin owns that phase).
        assertEquals(0f, ReactionOverlayTiming.waveRotationDegrees(-10), 0f)
        assertEquals(0f, ReactionOverlayTiming.waveRotationDegrees(0), 0f)
        assertEquals(0f, ReactionOverlayTiming.waveRotationDegrees(enter - 1), 0f)
        // Zero at the very start of the hold...
        assertEquals(0f, ReactionOverlayTiming.waveRotationDegrees(enter), 0.001f)
        // ...and back to zero once the hold ends (the exit fade + beyond).
        assertEquals(0f, ReactionOverlayTiming.waveRotationDegrees(enter + hold), 0f)
        assertEquals(0f, ReactionOverlayTiming.waveRotationDegrees(ReactionOverlayTiming.TOTAL_MS), 0f)
    }

    @Test
    fun wave_rock_stays_within_amplitude_and_actually_swings_both_ways() {
        val enter = ReactionOverlayTiming.ENTER_MS
        val hold = ReactionOverlayTiming.HOLD_MS
        val amp = ReactionOverlayTiming.WAVE_ROCK_DEGREES
        var sawPositive = false
        var sawNegative = false
        var t = enter
        while (t < enter + hold) {
            val deg = ReactionOverlayTiming.waveRotationDegrees(t)
            assertTrue("rock $deg exceeds ±$amp at $t", deg in -amp - 0.001f..amp + 0.001f)
            if (deg > amp * 0.5f) sawPositive = true
            if (deg < -amp * 0.5f) sawNegative = true
            t += 5
        }
        // It rocks to BOTH sides (a wave, not a lean).
        assertTrue("never rocked right", sawPositive)
        assertTrue("never rocked left", sawNegative)
    }

    @Test
    fun wave_rock_completes_the_configured_number_of_cycles() {
        val enter = ReactionOverlayTiming.ENTER_MS
        val hold = ReactionOverlayTiming.HOLD_MS
        val cycles = ReactionOverlayTiming.WAVE_ROCK_CYCLES
        // Count sign-crossings of the sine across the hold. N full periods have 2N
        // zeros in the OPEN interval plus one at each endpoint; the hold's END zero
        // lands exactly on the excluded endpoint (the rock returns to centre), so the
        // interior sign-crossings number 2N - 1.
        var crossings = 0
        var prev = ReactionOverlayTiming.waveRotationDegrees(enter + 1)
        var t = enter + 2
        while (t < enter + hold) {
            val cur = ReactionOverlayTiming.waveRotationDegrees(t)
            if (prev <= 0f && cur > 0f || prev >= 0f && cur < 0f) crossings++
            prev = cur
            t += 1
        }
        assertEquals(2 * cycles - 1, crossings)
    }

    @Test
    fun wave_rock_is_zero_for_a_zero_hold() {
        assertEquals(0f, ReactionOverlayTiming.waveRotationDegrees(100, 0L), 0f)
    }
}
