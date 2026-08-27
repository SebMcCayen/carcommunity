package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking the two proof fixes with the crown geofence on BOTH halves (#911), and
 * the fail-closed behaviour during the frame before the tapped crown latches.
 */
class CrownProofSelectionTest {

    private val base = 1_700_000_000_000L
    private val crownLat = 57.5
    private val crownLon = 12.0

    private val crown =
        CrownSpawn(
            id = "c1",
            latitude = crownLat,
            longitude = crownLon,
            rarity = CrownRarity.COMMON,
            rewardPoints = 100,
            collectRadiusMeters = CrownSpawnLimits.COLLECT_RADIUS_METERS,
            expiresAtMillis = base + 6 * 60 * 60 * 1000,
        )

    /**
     * A fix [metresEast] east of the crown. At 57.5° latitude 0.001° of longitude
     * is ~59.8 m, so a metre offset becomes a coordinate the haversine reads back
     * as (approximately) that far out.
     */
    private fun eastFix(offsetSeconds: Long, metresEast: Double) =
        CrownFix(
            latitude = crownLat,
            longitude = crownLon + metresEast / 59_800.0,
            recordedAtMillis = base + offsetSeconds * 1000,
        )

    @Test
    fun `a null crown fails closed - no fix is in range`() {
        val predicate = CrownProofSelection.inRangePredicate(null)
        // Even a fix sitting exactly on the crown is rejected while the crown is
        // unknown: there is no geofence to judge it against yet.
        assertFalse(predicate(eastFix(0, metresEast = 0.0)))
        assertFalse(predicate(eastFix(0, metresEast = 200.0)))
    }

    @Test
    fun `a latched crown applies the real geofence`() {
        val predicate = CrownProofSelection.inRangePredicate(crown)
        assertTrue("on the crown is in range", predicate(eastFix(0, metresEast = 10.0)))
        assertFalse("180 m out is not in range", predicate(eastFix(0, metresEast = 180.0)))
    }

    @Test
    fun `during the crown-null window no proof pair is selected`() {
        // The tracker already holds a fully in-range, time-valid pair — so an
        // ungated selection WOULD read as Ready. While the crown is still null the
        // selection must withhold the partner (fail closed), so the popup shows
        // confirming, never a Ready button, for that frame (#911 must not sneak
        // back in the pre-latch window).
        val tracker = CrownFixTracker()
        tracker.record(eastFix(0, metresEast = 15.0))
        tracker.record(eastFix(5, metresEast = 5.0))
        val now = base + 5_000

        val (current, previous) = CrownProofSelection.selectFixes(tracker, now, crown = null)
        assertNull("no proof partner while the crown is unknown", previous)
        assertFalse(
            "the withheld pair must not be a usable dwell proof",
            current != null && previous != null,
        )
    }

    @Test
    fun `once the crown latches the in-range pair is selected`() {
        val tracker = CrownFixTracker()
        tracker.record(eastFix(0, metresEast = 15.0))
        tracker.record(eastFix(5, metresEast = 5.0))
        val now = base + 5_000

        // Same tracker, same instant — only the crown has latched. The pair must
        // now be offered, proving fail-closed does not strand the popup in
        // confirming once the geofence is known.
        val (current, previous) = CrownProofSelection.selectFixes(tracker, now, crown)
        assertNotNull("current fix", current)
        assertNotNull("proof partner once the crown is known", previous)
        assertTrue(CrownCollectGate.isDwellProofUsable(previous!!, current!!))
    }

    @Test
    fun `an out-of-range partner is never selected even once the crown latches`() {
        // Approach-era fix ~180 m out, then a dead-on current. This is the raw
        // #911 shape: the server would refuse the pair outside_radius because the
        // PREVIOUS fix is outside the ring. The selection must hand back a null
        // partner so the gate stays in confirming rather than a false Ready.
        val tracker = CrownFixTracker()
        tracker.record(eastFix(0, metresEast = 180.0))
        tracker.record(eastFix(5, metresEast = 0.0))
        val now = base + 5_000

        val (current, previous) = CrownProofSelection.selectFixes(tracker, now, crown)
        assertNotNull("current fix still drives the distance line", current)
        assertNull("the out-of-range earlier fix must not be a partner", previous)
    }

    @Test
    fun `re-selecting live surfaces the pair the moment dwell accrues - no reset`() {
        // #993: the popup stays open, the member is in range and standing still, and
        // the dwell pair must go usable WITHOUT a close/reopen. Feeding an
        // accumulating series of in-range fixes and re-running selectFixes on each
        // tick (as the open-popup loop now does) must flip from "confirming" (null
        // partner) to a usable pair the instant a partner has aged MIN_DWELL in —
        // against the SAME tracker instance, never a fresh one.
        val tracker = CrownFixTracker()

        // t=0: first in-range fix. No partner has aged in yet → confirming.
        tracker.record(eastFix(0, metresEast = 10.0))
        CrownProofSelection.selectFixes(tracker, base + 0, crown).let { (cur, prev) ->
            assertNotNull("current fix drives the distance line immediately", cur)
            assertNull("no partner has aged in at t=0", prev)
        }

        // t=2 (< MIN_DWELL of 4s): still no usable partner.
        tracker.record(eastFix(2, metresEast = 8.0))
        CrownProofSelection.selectFixes(tracker, base + 2_000, crown).let { (_, prev) ->
            assertNull("2s < MIN_DWELL, still confirming", prev)
        }

        // t=4 (== MIN_DWELL): the t=0 fix is now a valid partner. Re-selecting on
        // this tick — no reset, same tracker — must surface a usable pair.
        tracker.record(eastFix(4, metresEast = 6.0))
        CrownProofSelection.selectFixes(tracker, base + 4_000, crown).let { (cur, prev) ->
            assertNotNull("current fix", cur)
            assertNotNull("a partner has aged MIN_DWELL in — pair goes live", prev)
            assertTrue(
                "the live-selected pair is a usable dwell proof",
                CrownCollectGate.isDwellProofUsable(prev!!, cur!!),
            )
        }
    }
}
