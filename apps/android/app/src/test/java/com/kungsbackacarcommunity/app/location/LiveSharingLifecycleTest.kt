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
}
