package com.kungsbackacarcommunity.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the OPTIMISTIC live-start overlay ([OptimisticLiveStart]).
 *
 * This is the whole reason the logic was pulled out of the shell composable: the
 * behaviour that matters is a set of async transitions (tap → in flight →
 * settled → confirmed / failed / timed out) that cannot be driven locally
 * through Compose, but is trivially checkable as pure functions over a clock.
 *
 * The properties guarded here are exactly the ones a fake STOP sign depends on:
 * a start is claimed instantly, a failure takes it back, a hung start expires, a
 * double tap starts once, and the observed session always wins in the end.
 */
class OptimisticLiveStartTest {
    private val t0 = 1_000_000L

    @Test
    fun tap_immediatelyCountsAsSharing_beforeAnySessionIsObserved() {
        val decision = OptimisticLiveStart.request(LiveStartAttempt.None, t0, observedSharing = false)

        assertTrue("the tap must actually issue the start", decision.proceed)
        assertEquals(LiveStartAttempt.InFlight(t0), decision.attempt)
        // The point of the whole exercise: sharing is TRUE on the same frame,
        // with no session observed at all.
        assertTrue(
            OptimisticLiveStart.isSharing(
                observedSharing = false,
                current = decision.attempt,
                nowMillis = t0,
            ),
        )
    }

    @Test
    fun secondTapWhileInFlight_doesNotStartAgain() {
        val first = OptimisticLiveStart.request(LiveStartAttempt.None, t0, observedSharing = false)
        val second = OptimisticLiveStart.request(first.attempt, t0 + 200, observedSharing = false)

        assertFalse("a double tap must issue exactly one start", second.proceed)
        assertEquals("and must not restart the clock", first.attempt, second.attempt)
    }

    @Test
    fun tapWhileAlreadySharing_doesNotStartAgain() {
        val decision = OptimisticLiveStart.request(LiveStartAttempt.None, t0, observedSharing = true)

        assertFalse(decision.proceed)
        assertEquals(LiveStartAttempt.None, decision.attempt)
    }

    @Test
    fun tapAfterAnEarlierAttemptExpired_startsAgain() {
        val stale = LiveStartAttempt.InFlight(t0)
        val now = t0 + OptimisticLiveStart.IN_FLIGHT_TIMEOUT_MS + 1

        val decision = OptimisticLiveStart.request(stale, now, observedSharing = false)

        assertTrue(decision.proceed)
        assertEquals(LiveStartAttempt.InFlight(now), decision.attempt)
    }

    @Test
    fun failedStart_revertsToNotSharingImmediately() {
        val inFlight = LiveStartAttempt.InFlight(t0)

        val after = OptimisticLiveStart.failed()

        assertEquals(LiveStartAttempt.None, after)
        assertTrue(OptimisticLiveStart.isSharing(false, inFlight, t0 + 100))
        assertFalse(
            "a failed start must never leave a STOP sign with no session behind it",
            OptimisticLiveStart.isSharing(false, after, t0 + 100),
        )
    }

    @Test
    fun hungStart_expiresAtTheInFlightCeiling() {
        val inFlight = LiveStartAttempt.InFlight(t0)
        val deadline = t0 + OptimisticLiveStart.IN_FLIGHT_TIMEOUT_MS

        assertEquals(deadline, OptimisticLiveStart.pendingUntilMillis(inFlight))
        assertTrue(OptimisticLiveStart.isSharing(false, inFlight, deadline - 1))
        assertFalse(
            "a start that never answers must not strand the UI in a fake sharing state",
            OptimisticLiveStart.isSharing(false, inFlight, deadline),
        )
    }

    @Test
    fun successfulStart_holdsOnlyForTheEchoWindow() {
        val settled = OptimisticLiveStart.settled(LiveStartAttempt.InFlight(t0), t0 + 800)
        val deadline = t0 + 800 + OptimisticLiveStart.ECHO_GRACE_MS

        assertEquals(LiveStartAttempt.Settled(requestedAtMillis = t0, settledAtMillis = t0 + 800), settled)
        assertEquals(deadline, OptimisticLiveStart.pendingUntilMillis(settled))
        assertTrue(OptimisticLiveStart.isSharing(false, settled, deadline - 1))
        // Succeeded, but no session ever appeared for this caller (live-share
        // disabled server-side, a convoy that was still forming): revert.
        assertFalse(OptimisticLiveStart.isSharing(false, settled, deadline))
    }

    @Test
    fun settling_afterTheAttemptWasDropped_doesNotResurrectIt() {
        // Stop was tapped while the start was still in flight.
        val settled = OptimisticLiveStart.settled(LiveStartAttempt.None, t0 + 900)

        assertEquals(LiveStartAttempt.None, settled)
    }

    @Test
    fun observedSession_dropsTheOverlayAndTakesOver() {
        val settled = LiveStartAttempt.Settled(requestedAtMillis = t0, settledAtMillis = t0 + 500)

        val reconciled = OptimisticLiveStart.reconcile(settled, observedSharing = true)

        assertEquals(LiveStartAttempt.None, reconciled)
        // Still sharing — but now on the real session, not the overlay.
        assertTrue(OptimisticLiveStart.isSharing(true, reconciled, t0 + 600))
    }

    @Test
    fun observedNotSharing_leavesAPendingAttemptAlone() {
        val inFlight = LiveStartAttempt.InFlight(t0)

        assertEquals(inFlight, OptimisticLiveStart.reconcile(inFlight, observedSharing = false))
    }

    @Test
    fun sessionBarTicksFromTheTap_untilTheRealStartIsKnown() {
        val inFlight = LiveStartAttempt.InFlight(t0)

        // No observed start yet: the bar must still be composable, or the STOP
        // sign would show with the bar hidden.
        assertEquals(
            t0,
            OptimisticLiveStart.sessionStartMillis(
                observedStartMillis = null,
                current = inFlight,
                nowMillis = t0 + 300,
            ),
        )
        // The elapsed time does NOT restart when the command returns.
        val settled = OptimisticLiveStart.settled(inFlight, t0 + 800)
        assertEquals(
            t0,
            OptimisticLiveStart.sessionStartMillis(null, settled, t0 + 900),
        )
        // The real start always wins once it is known.
        assertEquals(
            t0 - 5_000L,
            OptimisticLiveStart.sessionStartMillis(t0 - 5_000L, settled, t0 + 900),
        )
    }

    @Test
    fun sessionBarHasNothingToTickFrom_whenNothingIsPending() {
        assertNull(OptimisticLiveStart.sessionStartMillis(null, LiveStartAttempt.None, t0))
        assertNull(
            "an expired attempt is not a start time",
            OptimisticLiveStart.sessionStartMillis(
                observedStartMillis = null,
                current = LiveStartAttempt.InFlight(t0),
                nowMillis = t0 + OptimisticLiveStart.IN_FLIGHT_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun idleState_neverClaimsSharingAndHasNoDeadline() {
        assertNull(OptimisticLiveStart.pendingUntilMillis(LiveStartAttempt.None))
        assertFalse(OptimisticLiveStart.isPending(LiveStartAttempt.None, t0))
        assertFalse(OptimisticLiveStart.isSharing(false, LiveStartAttempt.None, t0))
        assertTrue(
            "the observed session alone still decides",
            OptimisticLiveStart.isSharing(true, LiveStartAttempt.None, t0),
        )
    }

    /**
     * The convoy case: a member who taps nothing (auto-started server-side by
     * someone ELSE's convoy activation) has no attempt at all, so the pure
     * observer path is untouched — the overlay can only ever ADD to what the
     * observed session already says.
     */
    @Test
    fun pureObserverPath_isUnaffectedByTheOverlay() {
        for (now in listOf(t0, t0 + 60_000L)) {
            assertTrue(OptimisticLiveStart.isSharing(true, LiveStartAttempt.None, now))
            assertFalse(OptimisticLiveStart.isSharing(false, LiveStartAttempt.None, now))
        }
    }
}
