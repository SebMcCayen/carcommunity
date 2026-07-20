package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.live.LiveSessionDuration
import com.kungsbackacarcommunity.app.live.LiveSessionInfo
import com.kungsbackacarcommunity.app.live.LiveSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The foreground service's lifecycle state machine — every way live sharing can
 * start and end, as pure logic. A real device is required to observe the service
 * itself under Doze or process death; this covers the decisions it makes.
 */
class LiveSharingLifecycleTest {

    private val now = 1_000_000L

    private fun session(
        status: LiveSessionStatus = LiveSessionStatus.ACTIVE,
        expiresAtMillis: Long? = now + ONE_HOUR,
        duration: LiveSessionDuration? = LiveSessionDuration.ONE_HOUR,
    ) = LiveSessionInfo(
        sessionId = "s1",
        status = status,
        duration = duration,
        expiresAtMillis = expiresAtMillis,
    )

    /** Asserts a Continue decision and returns its remaining-seconds payload. */
    private fun assertContinue(decision: LiveSharingDecision): Long? {
        if (decision !is LiveSharingDecision.Continue) {
            throw AssertionError("expected Continue, was $decision")
        }
        return decision.remainingSeconds
    }

    private fun assertStopped(
        reason: LiveSharingStopReason,
        decision: LiveSharingDecision,
    ) = assertEquals(LiveSharingDecision.Stop(reason), decision)

    @Test
    fun `active session keeps sharing and reports the remaining time`() {
        val remaining = assertContinue(LiveSharingLifecycle().onObservation(true, session(), now))

        assertEquals(3_600L, remaining)
    }

    @Test
    fun `manual stop ends sharing`() {
        val lifecycle = LiveSharingLifecycle()
        lifecycle.onObservation(true, session(), now)

        assertStopped(
            LiveSharingStopReason.SESSION_ENDED,
            lifecycle.onObservation(true, session(status = LiveSessionStatus.STOPPED), now),
        )
    }

    @Test
    fun `a session the backend already marked expired ends sharing`() {
        assertStopped(
            LiveSharingStopReason.SESSION_ENDED,
            LiveSharingLifecycle()
                .onObservation(true, session(status = LiveSessionStatus.EXPIRED), now),
        )
    }

    @Test
    fun `sign-out ends sharing immediately even with an active session`() {
        val lifecycle = LiveSharingLifecycle()
        lifecycle.onObservation(true, session(), now)

        assertStopped(
            LiveSharingStopReason.SIGNED_OUT,
            lifecycle.onObservation(false, session(), now),
        )
    }

    @Test
    fun `a ticking clock expires the session with no further database traffic`() {
        val lifecycle = LiveSharingLifecycle()
        // One observation of a 1h session, then only clock ticks — exactly what a
        // backgrounded phone with the screen off sees.
        assertContinue(lifecycle.onObservation(true, session(), now))

        assertContinue(lifecycle.onTick(true, now + ONE_HOUR - 1))
        assertStopped(
            LiveSharingStopReason.EXPIRED,
            lifecycle.onTick(true, now + ONE_HOUR),
        )
    }

    @Test
    fun `expiry wins over the absent-session grace window`() {
        val lifecycle = LiveSharingLifecycle(absentGraceMillis = 60_000L)
        lifecycle.onObservation(true, session(expiresAtMillis = now + 10_000L), now)
        // Connection drops (null) BEFORE the session expires; the grace window
        // must not extend the user's chosen sharing window.
        lifecycle.onObservation(true, null, now + 1_000L)

        assertStopped(
            LiveSharingStopReason.EXPIRED,
            lifecycle.onTick(true, now + 10_000L),
        )
    }

    @Test
    fun `a brief unreadable session does not stop sharing`() {
        val lifecycle = LiveSharingLifecycle(absentGraceMillis = 60_000L)
        lifecycle.onObservation(true, session(), now)

        // Tunnel: the repository maps the read error to null.
        assertContinue(lifecycle.onObservation(true, null, now + 1_000L))
        assertContinue(lifecycle.onTick(true, now + 30_000L))

        // Back on data before the grace window closes — sharing never broke...
        assertContinue(lifecycle.onObservation(true, session(), now + 40_000L))
        // ...and a later blip gets a FRESH grace window rather than the old one.
        lifecycle.onObservation(true, null, now + 41_000L)
        assertContinue(lifecycle.onTick(true, now + 100_000L))
    }

    @Test
    fun `a session that stays absent past the grace window ends sharing`() {
        val lifecycle = LiveSharingLifecycle(absentGraceMillis = 60_000L)
        lifecycle.onObservation(true, session(), now)
        lifecycle.onObservation(true, null, now + 1_000L)

        assertStopped(
            LiveSharingStopReason.SESSION_ABSENT,
            lifecycle.onTick(true, now + 61_000L),
        )
    }

    @Test
    fun `a remotely erased session ends sharing even if it was never seen`() {
        val lifecycle = LiveSharingLifecycle(absentGraceMillis = 60_000L)
        // Restarted after process death: the redelivered intent gives a uid, but
        // the session was ended while the process was dead, so the node is gone.
        assertContinue(lifecycle.onObservation(true, null, now))

        assertStopped(
            LiveSharingStopReason.SESSION_ABSENT,
            lifecycle.onTick(true, now + 60_000L),
        )
    }

    @Test
    fun `an unparseable expiry keeps sharing rather than stranding the user`() {
        val remaining =
            assertContinue(
                LiveSharingLifecycle().onObservation(true, session(expiresAtMillis = null), now),
            )

        assertEquals(null, remaining)
    }

    @Test
    fun `an unparseable expiry still cannot share past the four-hour ceiling`() {
        val lifecycle = LiveSharingLifecycle()
        val noExpiry = session(expiresAtMillis = null)
        lifecycle.onObservation(true, noExpiry, now)

        assertContinue(lifecycle.onObservation(true, noExpiry, now + 4 * ONE_HOUR))
        assertStopped(
            LiveSharingStopReason.EXPIRED,
            lifecycle.onObservation(true, noExpiry, now + LiveSharingLifecycle.MAX_RUNTIME_MILLIS),
        )
    }

    private companion object {
        const val ONE_HOUR = 3_600_000L
    }

    /**
     * Regression: the hard ceiling must be anchored to the SESSION, not to the
     * service instance. A process kill plus the START_REDELIVER_INTENT restart
     * builds a NEW LiveSharingLifecycle; if the anchor lived on that instance,
     * each restart would hand a session with an unparseable expiry (which
     * isSharing treats as still sharing) another full 4h05m, so repeated kills
     * could extend background location sharing without limit.
     */
    @Test
    fun `hard ceiling survives a service restart within the same session`() {
        val store = InMemorySharingAnchorStore()
        val noExpiry = session(expiresAtMillis = null)

        // First run: starts the clock, still well inside the ceiling.
        val first = LiveSharingLifecycle(anchorStore = store)
        assertContinue(first.onObservation(true, noExpiry, now))
        assertContinue(
            first.onObservation(true, noExpiry, now + LiveSharingLifecycle.MAX_RUNTIME_MILLIS / 2),
        )

        // Process killed; the service is restarted with the same session, most
        // of the budget already spent. The restarted instance must inherit it.
        val restarted = LiveSharingLifecycle(anchorStore = store)
        assertContinue(
            restarted.onObservation(
                true,
                noExpiry,
                now + LiveSharingLifecycle.MAX_RUNTIME_MILLIS - 1,
            ),
        )
        assertStopped(
            LiveSharingStopReason.EXPIRED,
            restarted.onObservation(true, noExpiry, now + LiveSharingLifecycle.MAX_RUNTIME_MILLIS),
        )
    }

    /** A genuinely new session gets its own budget rather than inheriting one. */
    @Test
    fun `a new session id resets the ceiling`() {
        val store = InMemorySharingAnchorStore()
        val old = session(expiresAtMillis = null)
        val first = LiveSharingLifecycle(anchorStore = store)
        assertContinue(first.onObservation(true, old, now))

        val fresh =
            LiveSessionInfo(
                sessionId = "s2",
                status = LiveSessionStatus.ACTIVE,
                duration = LiveSessionDuration.ONE_HOUR,
                expiresAtMillis = null,
            )
        val later = LiveSharingLifecycle(anchorStore = store)
        val wellPastTheOldCeiling = now + LiveSharingLifecycle.MAX_RUNTIME_MILLIS + 1
        assertContinue(later.onObservation(true, fresh, wellPastTheOldCeiling))
    }

    /** Once sharing ends the anchor is dropped, not left for a later session. */
    @Test
    fun `stopping clears the anchor`() {
        val store = InMemorySharingAnchorStore()
        val lifecycle = LiveSharingLifecycle(anchorStore = store)
        assertContinue(lifecycle.onObservation(true, session(expiresAtMillis = null), now))
        assertStopped(
            LiveSharingStopReason.SESSION_ENDED,
            lifecycle.onObservation(true, session(status = LiveSessionStatus.STOPPED), now),
        )
        // Cleared, so the same id observed later starts a fresh budget.
        assertEquals(now + 5_000L, store.anchorFor("s1", now + 5_000L))
    }


    /**
     * Regression: a transient read failure must NOT reset the ceiling. The
     * repository maps read errors to a null session, so SESSION_ABSENT can mean
     * "tunnel" rather than "ended". Clearing the anchor there would let a
     * restart within the same session re-anchor a fresh budget — reopening the
     * hole the anchor exists to close.
     */
    @Test
    fun `an absent session does not reset the ceiling`() {
        val store = InMemorySharingAnchorStore()
        val noExpiry = session(expiresAtMillis = null)
        val lifecycle = LiveSharingLifecycle(anchorStore = store)
        assertContinue(lifecycle.onObservation(true, noExpiry, now))

        // The node goes unreadable — the first null only starts the grace
        // window, and it has to persist past it to stop.
        assertContinue(lifecycle.onObservation(true, null, now))
        assertStopped(
            LiveSharingStopReason.SESSION_ABSENT,
            lifecycle.onObservation(
                true,
                null,
                now + LiveSharingLifecycle.ABSENT_GRACE_MILLIS,
            ),
        )

        // The read recovers and the service restarts on the SAME session. The
        // original anchor must still bound it.
        val restarted = LiveSharingLifecycle(anchorStore = store)
        assertStopped(
            LiveSharingStopReason.EXPIRED,
            restarted.onObservation(true, noExpiry, now + LiveSharingLifecycle.MAX_RUNTIME_MILLIS),
        )
    }

    /** Sign-out is also not proof the session ended, so it keeps the anchor. */
    @Test
    fun `signing out does not reset the ceiling`() {
        val store = InMemorySharingAnchorStore()
        val noExpiry = session(expiresAtMillis = null)
        val lifecycle = LiveSharingLifecycle(anchorStore = store)
        assertContinue(lifecycle.onObservation(true, noExpiry, now))
        assertStopped(LiveSharingStopReason.SIGNED_OUT, lifecycle.onTick(false, now + 1_000L))

        val restarted = LiveSharingLifecycle(anchorStore = store)
        assertStopped(
            LiveSharingStopReason.EXPIRED,
            restarted.onObservation(true, noExpiry, now + LiveSharingLifecycle.MAX_RUNTIME_MILLIS),
        )
    }

}
