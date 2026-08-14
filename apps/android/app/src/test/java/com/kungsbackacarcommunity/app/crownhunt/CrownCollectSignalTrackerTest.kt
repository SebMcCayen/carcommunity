package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a stuck crown collect is worth a telemetry signal — the dwell-not-ready
 * cause that never reaches the server.
 *
 * The tracker's whole job is to fire ONCE, after enough refusals, and never
 * again for the same crown. That "once and only once" is the property that keeps
 * it from spamming the client-error pipeline, so it is pinned here rather than
 * discovered by tapping a disabled button.
 */
class CrownCollectSignalTrackerTest {

    /** Below the threshold, nothing is reported — one impatient double-tap is normal. */
    @Test
    fun `below the threshold there is no signal`() {
        val tracker = CrownCollectSignalTracker(threshold = 3)
        assertNull(tracker.onRefused())
        assertNull(tracker.onRefused())
    }

    /** The threshold-crossing refusal reports the running count, exactly once. */
    @Test
    fun `crossing the threshold reports the count a single time`() {
        val tracker = CrownCollectSignalTracker(threshold = 3)
        assertNull(tracker.onRefused())
        assertNull(tracker.onRefused())
        assertEquals(3, tracker.onRefused())
        // Every later refusal for the same crown is silent — no spam.
        assertNull(tracker.onRefused())
        assertNull(tracker.onRefused())
    }

    /** The default threshold is three. */
    @Test
    fun `the default threshold fires on the third refusal`() {
        val tracker = CrownCollectSignalTracker()
        assertNull(tracker.onRefused())
        assertNull(tracker.onRefused())
        assertEquals(CrownCollectSignalTracker.DEFAULT_THRESHOLD, tracker.onRefused())
    }

    /** The signal's feature and code are the stable strings the backend fingerprints. */
    @Test
    fun `the signal identifiers are stable`() {
        assertEquals("crownHunt.collect", CrownCollectSignalTracker.SIGNAL_FEATURE)
        assertEquals("crown_collect_dwell_not_ready", CrownCollectSignalTracker.SIGNAL_CODE)
    }
}
