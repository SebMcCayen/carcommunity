package com.kungsbackacarcommunity.app.coachmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure first-login coach-mark tour: the step sequence (at most
 * four tips), 1-based progress positions, last-step detection (which flips "Next"
 * to "Done"), and the clamped [CoachMarkTour.next] advance. Also pins the two
 * product invariants behind issue #845: the drive-recording tip exists and is
 * shown FIRST. Keeps the ordering honest without a Compose/instrumented harness.
 */
class CoachMarkTourTest {

    @Test
    fun `there are at most four ordered steps starting at Drive`() {
        assertTrue("tour must show at most 4 tips", CoachMarkStep.COUNT <= 4)
        assertEquals(4, CoachMarkStep.COUNT)
        assertEquals(CoachMarkStep.Drive, CoachMarkStep.FIRST)
        assertEquals(
            listOf(
                CoachMarkStep.Drive,
                CoachMarkStep.Social,
                CoachMarkStep.Explore,
                CoachMarkStep.History,
            ),
            CoachMarkTour.ORDERED,
        )
    }

    @Test
    fun `the drive-recording tip is present and shown first (issue 845)`() {
        // #845: newcomers must be told the centre control RECORDS their drive.
        assertTrue(CoachMarkTour.ORDERED.contains(CoachMarkStep.Drive))
        assertEquals(CoachMarkStep.Drive, CoachMarkTour.ORDERED.first())
    }

    @Test
    fun `steps are unique`() {
        assertEquals(CoachMarkTour.ORDERED.size, CoachMarkTour.ORDERED.toSet().size)
    }

    @Test
    fun `position is 1-based over all steps`() {
        assertEquals(1, CoachMarkTour.position(CoachMarkStep.Drive))
        assertEquals(2, CoachMarkTour.position(CoachMarkStep.Social))
        assertEquals(3, CoachMarkTour.position(CoachMarkStep.Explore))
        assertEquals(4, CoachMarkTour.position(CoachMarkStep.History))
    }

    @Test
    fun `only the final step is last`() {
        assertFalse(CoachMarkTour.isLast(CoachMarkStep.Drive))
        assertFalse(CoachMarkTour.isLast(CoachMarkStep.Social))
        assertFalse(CoachMarkTour.isLast(CoachMarkStep.Explore))
        assertTrue(CoachMarkTour.isLast(CoachMarkStep.History))
    }

    @Test
    fun `next advances one step and clamps at the last`() {
        assertEquals(CoachMarkStep.Social, CoachMarkTour.next(CoachMarkStep.Drive))
        assertEquals(CoachMarkStep.Explore, CoachMarkTour.next(CoachMarkStep.Social))
        assertEquals(CoachMarkStep.History, CoachMarkTour.next(CoachMarkStep.Explore))
        // Already last: next() returns the same step (the caller finishes instead).
        assertEquals(CoachMarkStep.History, CoachMarkTour.next(CoachMarkStep.History))
    }

    @Test
    fun `walking next from the first reaches the last in COUNT minus one hops`() {
        var step = CoachMarkStep.FIRST
        var hops = 0
        while (!CoachMarkTour.isLast(step)) {
            step = CoachMarkTour.next(step)
            hops++
        }
        assertEquals(CoachMarkStep.COUNT - 1, hops)
        assertEquals(CoachMarkStep.History, step)
    }
}
