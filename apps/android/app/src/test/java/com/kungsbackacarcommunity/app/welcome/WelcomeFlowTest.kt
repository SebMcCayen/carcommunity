package com.kungsbackacarcommunity.app.welcome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure first-login welcome step progression: 1-based
 * position labels, last-step detection (which flips "Next" to "Get started"),
 * and the clamped [WelcomeFlow.next] advance. Keeps the ordering logic honest
 * without a Compose/instrumented harness.
 */
class WelcomeFlowTest {

    @Test
    fun `there are four ordered steps starting at Welcome`() {
        assertEquals(4, WelcomeStep.COUNT)
        assertEquals(WelcomeStep.Welcome, WelcomeStep.FIRST)
        assertEquals(
            listOf(
                WelcomeStep.Welcome,
                WelcomeStep.Map,
                WelcomeStep.Membership,
                WelcomeStep.Profile,
            ),
            WelcomeStep.entries.toList(),
        )
    }

    @Test
    fun `position is 1-based over all steps`() {
        assertEquals(1, WelcomeFlow.position(WelcomeStep.Welcome))
        assertEquals(2, WelcomeFlow.position(WelcomeStep.Map))
        assertEquals(3, WelcomeFlow.position(WelcomeStep.Membership))
        assertEquals(4, WelcomeFlow.position(WelcomeStep.Profile))
    }

    @Test
    fun `only the final step is last`() {
        assertFalse(WelcomeFlow.isLast(WelcomeStep.Welcome))
        assertFalse(WelcomeFlow.isLast(WelcomeStep.Map))
        assertFalse(WelcomeFlow.isLast(WelcomeStep.Membership))
        assertTrue(WelcomeFlow.isLast(WelcomeStep.Profile))
    }

    @Test
    fun `next advances one step and clamps at the last`() {
        assertEquals(WelcomeStep.Map, WelcomeFlow.next(WelcomeStep.Welcome))
        assertEquals(WelcomeStep.Membership, WelcomeFlow.next(WelcomeStep.Map))
        assertEquals(WelcomeStep.Profile, WelcomeFlow.next(WelcomeStep.Membership))
        // Already last: next() returns the same step (the caller finishes instead).
        assertEquals(WelcomeStep.Profile, WelcomeFlow.next(WelcomeStep.Profile))
    }

    @Test
    fun `walking next from the first reaches the last in COUNT minus one hops`() {
        var step = WelcomeStep.FIRST
        var hops = 0
        while (!WelcomeFlow.isLast(step)) {
            step = WelcomeFlow.next(step)
            hops++
        }
        assertEquals(WelcomeStep.COUNT - 1, hops)
        assertEquals(WelcomeStep.Profile, step)
    }
}
