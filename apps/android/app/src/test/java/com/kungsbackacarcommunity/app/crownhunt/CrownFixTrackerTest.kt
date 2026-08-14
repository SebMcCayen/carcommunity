package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rolling pair of fixes a claim is proved with.
 *
 * `crownHunt.claimSpawn` will not accept a single sample — a reported speed of
 * zero is just a number the client sent — so it wants two fixes 4..300 s apart
 * and derives its own speed from the pair. This is the arithmetic that picks
 * them, unit-tested rather than discovered by sitting in a car park.
 */
class CrownFixTrackerTest {

    private val base = 1_700_000_000_000L

    private fun fix(offsetSeconds: Long, lat: Double = 57.5, accuracyMeters: Double? = null) =
        CrownFix(
            latitude = lat,
            longitude = 12.0,
            recordedAtMillis = base + offsetSeconds * 1000,
            accuracyMeters = accuracyMeters,
        )

    @Test
    fun `before the minimum dwell there is no partner yet`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0))
        assertEquals(fix(0), tracker.latest)
        // Null is the honest answer for the first few seconds after arriving; the
        // UI turns it into "wait a moment", never into a refusal.
        assertNull(tracker.proofPartner())

        tracker.record(fix(2))
        assertNull("2 s is under the 4 s minimum", tracker.proofPartner())
    }

    @Test
    fun `once the window opens the pair is usable`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0))
        tracker.record(fix(5))

        val partner = tracker.proofPartner()
        assertEquals(fix(0), partner)
        assertTrue(CrownCollectGate.isDwellProofUsable(partner!!, tracker.latest!!))
    }

    /**
     * The NEWEST fix old enough is chosen, not the oldest available — the
     * regression this rule exists for.
     *
     * Holding the oldest would mean that after five minutes parked, every claim
     * was evaluated against a five-minute-old coordinate, which the server
     * rejects the moment it passes MAX_DWELL_SECONDS. A stationary member's
     * claim would then start failing for no reason they could see.
     */
    @Test
    fun `the pair stays as tight as the rule allows however long you sit there`() {
        val tracker = CrownFixTracker()
        for (t in 0..120 step 2) {
            tracker.record(fix(t.toLong()))
        }
        val partner = tracker.proofPartner()!!
        val gapSeconds = (tracker.latest!!.recordedAtMillis - partner.recordedAtMillis) / 1000
        assertTrue(
            "the pair spans $gapSeconds s — it should hug the 4 s minimum, not the 300 s cap",
            gapSeconds in CrownSpawnLimits.MIN_DWELL_SECONDS..(CrownSpawnLimits.MIN_DWELL_SECONDS + 2),
        )
        assertTrue(CrownCollectGate.isDwellProofUsable(partner, tracker.latest!!))
    }

    /**
     * A fix that arrives out of order (a clock adjustment, or a provider
     * replaying a cached sample) is DROPPED rather than accepted — inserting it
     * would let the pair span backwards in time, which the server reads as a
     * malformed claim.
     */
    @Test
    fun `an out-of-order fix is dropped rather than corrupting the pair`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(10))
        tracker.record(fix(3))

        assertEquals("the newer fix must stay latest", fix(10), tracker.latest)
    }

    /** Anything older than the dwell cap is pruned, so it cannot be picked. */
    @Test
    fun `fixes older than the dwell cap are forgotten`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0))
        tracker.record(fix(CrownSpawnLimits.MAX_DWELL_SECONDS + 60))

        val partner = tracker.proofPartner()
        assertNull("a fix beyond the cap must not be offered as a proof", partner)
    }

    /** Leaving the popup forgets everything, so a stale pair cannot prove a later dwell. */
    @Test
    fun `clearing forgets the pair`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0))
        tracker.record(fix(10))
        tracker.clear()

        assertNull(tracker.latest)
        assertNull(tracker.proofPartner())
    }

    // ---- Pre-warming ------------------------------------------------------

    /**
     * A tracker fed BEFORE a popup opens (the range poll pre-warming it) already
     * has a proof partner the instant the popup appears — the fix for the
     * "stand still, tap, tap, then it collects" lag.
     */
    @Test
    fun `a pre-warmed tracker offers a proof partner immediately`() {
        val tracker = CrownFixTracker()
        // Simulate the map's ongoing poll landing a few fixes before the popup.
        tracker.record(fix(0))
        tracker.record(fix(3))
        tracker.record(fix(6))

        val partner = tracker.proofPartner()!!
        val current = tracker.bestRecent()!!
        assertTrue(
            "a warm tracker should already have a usable pair",
            CrownCollectGate.isDwellProofUsable(partner, current),
        )
        assertEquals(0, tracker.secondsUntilProofReady())
    }

    /** With only a single fresh fix, the countdown reports the full minimum dwell. */
    @Test
    fun `the countdown reports the wait until a partner ages in`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0))
        assertEquals(
            CrownSpawnLimits.MIN_DWELL_SECONDS.toInt(),
            tracker.secondsUntilProofReady(),
        )
        // One second later a partner is still 3 s short.
        tracker.record(fix(1))
        assertEquals(3, tracker.secondsUntilProofReady())
    }

    // ---- Best-accuracy selection -----------------------------------------

    /**
     * The current fix prefers the best-accuracy sample in the settle window over
     * the raw latest, so one jittery reading does not decide the distance.
     */
    @Test
    fun `bestRecent prefers the most accurate recent fix over the latest`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0, accuracyMeters = 8.0))
        // A jittery latest with a huge accuracy radius must not win.
        tracker.record(fix(2, accuracyMeters = 60.0))

        assertEquals(8.0, tracker.bestRecent()!!.accuracyMeters!!, 0.0)
    }

    /**
     * The proof partner is likewise chosen by accuracy, not merely "newest old
     * enough": a settled earlier fix makes the pair the server judges tighter.
     */
    @Test
    fun `proofPartnerFor prefers the most accurate candidate in the dwell window`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0, accuracyMeters = 40.0))
        tracker.record(fix(1, accuracyMeters = 6.0))
        tracker.record(fix(8))

        val current = fix(8)
        val partner = tracker.proofPartnerFor(current)!!
        // Both fix(0) and fix(1) are >= 4 s older than fix(8); the 6 m one wins.
        assertEquals(6.0, partner.accuracyMeters!!, 0.0)
    }

    /** With no fix at all, there is no current and no partner. */
    @Test
    fun `an empty tracker has no current fix`() {
        val tracker = CrownFixTracker()
        assertNull(tracker.bestRecent())
        assertNull(tracker.proofPartnerFor(null))
    }
}
