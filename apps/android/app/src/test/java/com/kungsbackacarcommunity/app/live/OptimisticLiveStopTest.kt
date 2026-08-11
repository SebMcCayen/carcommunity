package com.kungsbackacarcommunity.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the OPTIMISTIC live-STOP overlay ([OptimisticLiveStop]) — the
 * mirror of [OptimisticLiveStartTest] and the pure core of #798's instant-stop.
 *
 * The properties guarded here are exactly the ones an instant hide of the sharing
 * chrome depends on: a stop is claimed instantly, a failure takes the claim back
 * (the STOP sign returns rather than hiding a still-live session), a hung stop
 * expires, a double tap keeps one deadline, and the observed session always wins
 * in the end.
 */
class OptimisticLiveStopTest {
    private val t0 = 1_000_000L

    @Test
    fun tap_immediatelyHidesSharing_whileTheSessionIsStillObserved() {
        val attempt = OptimisticLiveStop.request(LiveStopAttempt.None, t0)

        assertEquals(LiveStopAttempt.InFlight(t0), attempt)
        // The point of the exercise: the overlay counts as "stopping" on the same
        // frame, so a shell that subtracts it from observed sharing hides the
        // chrome at once even though the real session is still up.
        assertTrue(OptimisticLiveStop.isStopping(attempt, t0))
    }

    @Test
    fun secondTapWhileInFlight_keepsTheFirstAttemptAndDeadline() {
        val first = OptimisticLiveStop.request(LiveStopAttempt.None, t0)
        val second = OptimisticLiveStop.request(first, t0 + 200)

        assertEquals("a double tap must not restart the clock", first, second)
    }

    @Test
    fun tapAfterAnEarlierAttemptExpired_startsAgain() {
        val stale = LiveStopAttempt.InFlight(t0)
        val now = t0 + OptimisticLiveStop.IN_FLIGHT_TIMEOUT_MS + 1

        val attempt = OptimisticLiveStop.request(stale, now)

        assertEquals(LiveStopAttempt.InFlight(now), attempt)
    }

    @Test
    fun failedStop_revertsToSharingImmediately() {
        val inFlight = LiveStopAttempt.InFlight(t0)

        val after = OptimisticLiveStop.failed()

        assertEquals(LiveStopAttempt.None, after)
        assertTrue("still stopping before the revert", OptimisticLiveStop.isStopping(inFlight, t0 + 100))
        assertFalse(
            "a failed stop must not keep hiding a still-live session",
            OptimisticLiveStop.isStopping(after, t0 + 100),
        )
    }

    @Test
    fun hungStop_expiresAtTheInFlightCeiling() {
        val inFlight = LiveStopAttempt.InFlight(t0)
        val deadline = t0 + OptimisticLiveStop.IN_FLIGHT_TIMEOUT_MS

        assertEquals(deadline, OptimisticLiveStop.pendingUntilMillis(inFlight))
        assertTrue(OptimisticLiveStop.isStopping(inFlight, deadline - 1))
        assertFalse(
            "a stop that never answers must not hide the session forever",
            OptimisticLiveStop.isStopping(inFlight, deadline),
        )
    }

    @Test
    fun successfulStop_holdsOnlyForTheEchoWindow() {
        val settled = OptimisticLiveStop.settled(LiveStopAttempt.InFlight(t0), t0 + 800)
        val deadline = t0 + 800 + OptimisticLiveStop.ECHO_GRACE_MS

        assertEquals(LiveStopAttempt.Settled(settledAtMillis = t0 + 800), settled)
        assertEquals(deadline, OptimisticLiveStop.pendingUntilMillis(settled))
        assertTrue(OptimisticLiveStop.isStopping(settled, deadline - 1))
        // Succeeded, but no distinct stopped echo ever arrived (the session was
        // already gone): the overlay lapses and the observed truth takes over.
        assertFalse(OptimisticLiveStop.isStopping(settled, deadline))
    }

    @Test
    fun settling_afterTheAttemptWasDropped_doesNotResurrectIt() {
        // The session was observed stopped (or a fresh start took over) before the
        // callable returned.
        val settled = OptimisticLiveStop.settled(LiveStopAttempt.None, t0 + 900)

        assertEquals(LiveStopAttempt.None, settled)
    }

    @Test
    fun observedNotSharing_dropsTheOverlayAsTheStopLands() {
        val settled = LiveStopAttempt.Settled(settledAtMillis = t0 + 500)

        val reconciled = OptimisticLiveStop.reconcile(settled, observedSharing = false)

        assertEquals(LiveStopAttempt.None, reconciled)
        assertFalse(OptimisticLiveStop.isStopping(reconciled, t0 + 600))
    }

    @Test
    fun observedStillSharing_leavesAPendingAttemptAlone() {
        val inFlight = LiveStopAttempt.InFlight(t0)

        assertEquals(inFlight, OptimisticLiveStop.reconcile(inFlight, observedSharing = true))
    }

    @Test
    fun idleState_neverHidesSharingAndHasNoDeadline() {
        assertNull(OptimisticLiveStop.pendingUntilMillis(LiveStopAttempt.None))
        assertFalse(OptimisticLiveStop.isStopping(LiveStopAttempt.None, t0))
    }
}
