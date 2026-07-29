package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The live-session bar's two promises: the first frame reads 0:00, and the
 * readout only ever counts up.
 */
class LiveSessionElapsedTest {
    private val tap = 1_700_000_000_000L

    /** What the bar would render for a given anchor at a given moment. */
    private fun label(anchorMillis: Long?, nowMillis: Long): String =
        LiveSessionFormat.elapsedLabel(
            LiveSessionElapsed.elapsedMillis(requireNotNull(anchorMillis), nowMillis),
        )

    @Test
    fun firstFrameAfterATap_readsZero() {
        // The frame the user's tap produces: nothing observed yet, so the tap is
        // the only candidate — and it IS `now`.
        val anchor =
            LiveSessionElapsed.anchorMillis(
                latchedMillis = null,
                sharing = true,
                tapStartMillis = tap,
                observedStartMillis = null,
                nowMillis = tap,
            )

        assertEquals(tap, anchor)
        assertEquals("0:00", label(anchor, tap))
    }

    @Test
    fun serverClockSkew_cannotJumpTheReadoutOffZero() {
        // The regression: the observed start is reconstructed from a SERVER-minted
        // expiresAt, so a server clock running 19s behind the device's used to
        // land in the bar as "0:19" the moment the session echoed down.
        val serverStart = tap - 19_000L
        val latched =
            LiveSessionElapsed.anchorMillis(
                latchedMillis = null,
                sharing = true,
                tapStartMillis = tap,
                observedStartMillis = null,
                nowMillis = tap,
            )

        val afterEcho =
            LiveSessionElapsed.anchorMillis(
                latchedMillis = latched,
                sharing = true,
                // The attempt has been reconciled away by now — only the skewed
                // server start is on offer, and it must not be adopted.
                tapStartMillis = null,
                observedStartMillis = serverStart,
                nowMillis = tap + 900L,
            )

        assertEquals(tap, afterEcho)
        assertEquals("0:00", label(afterEcho, tap + 900L))
        assertEquals("0:01", label(afterEcho, tap + 1_000L))
    }

    @Test
    fun aStartInTheFuture_stillReadsZeroRatherThanCountingDown() {
        // Skew the other way: the server clock runs ahead, so the derived start is
        // in the future. Clamped, so the bar opens at 0:00 and counts up at once
        // instead of sitting on 0:00 for the length of the skew.
        val anchor =
            LiveSessionElapsed.anchorMillis(
                latchedMillis = null,
                sharing = true,
                tapStartMillis = null,
                observedStartMillis = tap + 19_000L,
                nowMillis = tap,
            )

        assertEquals(tap, anchor)
        assertEquals("0:00", label(anchor, tap))
        assertEquals("0:05", label(anchor, tap + 5_000L))
    }

    @Test
    fun theTapWinsOverAnObservedStart_onTheSameFrame() {
        assertEquals(
            tap,
            LiveSessionElapsed.anchorMillis(
                latchedMillis = null,
                sharing = true,
                tapStartMillis = tap,
                observedStartMillis = tap - 19_000L,
                nowMillis = tap,
            ),
        )
    }

    @Test
    fun aLatchedAnchorIsNeverRevised() {
        // Not even by a "better" candidate: any move is a visible jump in a
        // number the user is watching tick.
        val latched =
            LiveSessionElapsed.anchorMillis(
                latchedMillis = tap,
                sharing = true,
                tapStartMillis = tap - 60_000L,
                observedStartMillis = tap + 60_000L,
                nowMillis = tap + 5_000L,
            )

        assertEquals(tap, latched)
    }

    @Test
    fun anAlreadyRunningSession_countsFromItsObservedStart() {
        // Re-opening the app mid-session: there was no tap in this process, so the
        // observed start is all there is — and it must NOT be clamped to "now",
        // which would restart an hour-old session at 0:00.
        val startedAnHourAgo = tap - 60L * 60L * 1000L
        val anchor =
            LiveSessionElapsed.anchorMillis(
                latchedMillis = null,
                sharing = true,
                tapStartMillis = null,
                observedStartMillis = startedAnHourAgo,
                nowMillis = tap,
            )

        assertEquals(startedAnHourAgo, anchor)
        assertEquals("1h 00m", label(anchor, tap))
    }

    @Test
    fun theAnchorIsReleasedWhenSharingEnds_soTheNextSessionStartsAtZero() {
        assertNull(
            LiveSessionElapsed.anchorMillis(
                latchedMillis = tap,
                sharing = false,
                tapStartMillis = null,
                observedStartMillis = tap,
                nowMillis = tap + 5_000L,
            ),
        )
    }

    @Test
    fun noCandidateAtAll_composesNoBar() {
        assertNull(
            LiveSessionElapsed.anchorMillis(
                latchedMillis = null,
                sharing = true,
                tapStartMillis = null,
                observedStartMillis = null,
                nowMillis = tap,
            ),
        )
    }

    @Test
    fun elapsedIsNeverNegative() {
        assertEquals(0L, LiveSessionElapsed.elapsedMillis(tap, tap - 5_000L))
        assertEquals(0L, LiveSessionElapsed.elapsedMillis(tap, tap))
        assertEquals(5_000L, LiveSessionElapsed.elapsedMillis(tap, tap + 5_000L))
    }
}
