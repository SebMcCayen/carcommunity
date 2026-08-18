package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // The clock is read at the moment of the last fix — a fresh current.
        assertEquals(0, tracker.secondsUntilProofReady(base + 6_000))
    }

    /** With only a single fresh fix, the countdown reports the full minimum dwell. */
    @Test
    fun `the countdown reports the wait until a partner ages in`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0))
        // Clock at the fix's own instant — a fresh current, no partner yet.
        assertEquals(
            CrownSpawnLimits.MIN_DWELL_SECONDS.toInt(),
            tracker.secondsUntilProofReady(base),
        )
        // One second later a partner is still 3 s short.
        tracker.record(fix(1))
        assertEquals(3, tracker.secondsUntilProofReady(base + 1_000))
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

    // ---- Wall-clock freshness --------------------------------------------

    /**
     * The tracker only prunes on record(), so after a long idle a stale pair still
     * exists. The clock-aware readiness check must treat a MINUTES-old leftover as
     * ABSENT, so it can never seed the popup or enable Collect — the wrong-state
     * bug this feature exists to remove.
     */
    @Test
    fun `a minutes-old idle-leftover pair is ignored as stale`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0))
        tracker.record(fix(6))
        // At the time of the last fix the pair is fresh and ready.
        val freshNow = base + 6_000
        assertNotNull(tracker.bestRecent(freshNow))
        assertNotNull(tracker.proofPartner(freshNow))

        // Minutes later, with no new fix, the current is stale — nothing is ready.
        val staleNow = base + 200_000
        assertNull("a stale current must read as no fix", tracker.bestRecent(staleNow))
        assertNull("a stale pair must not read as ready", tracker.proofPartner(staleNow))
        assertEquals(
            "the wait resets to the full minimum dwell",
            CrownSpawnLimits.MIN_DWELL_SECONDS.toInt(),
            tracker.secondsUntilProofReady(staleNow),
        )

        // A fresh fix restores readiness immediately (the old partner is still in
        // the dwell window relative to it).
        tracker.record(CrownFix(57.5, 12.0, staleNow))
        assertNotNull(tracker.bestRecent(staleNow))
        assertNotNull(tracker.proofPartner(staleNow))
    }

    /**
     * A normal slightly-old fix (well inside the server's own freshness window)
     * must read as READY — the client threshold mirrors the server's, so it never
     * blocks a collect the server would accept. This is the over-correction guard:
     * a stricter cutoff would re-create the collect lag on slow-GPS devices.
     */
    @Test
    fun `a fix a few tens of seconds old is still usable, matching the server`() {
        val tracker = CrownFixTracker()
        tracker.record(fix(0))
        tracker.record(fix(6))
        // 30 s after the last fix: comfortably inside the 60 s server window.
        val now = base + 36_000
        assertNotNull("a 30 s-old fix must still be a current", tracker.bestRecent(now))
        assertNotNull("a 30 s-old pair must still be ready", tracker.proofPartner(now))
    }

    /**
     * bestRecent(now) prefers a more-accurate EARLIER sample, so freshness must
     * apply to the sample RETURNED — not only to `latest`. A fresh latest with a
     * stale (but more accurate) older sample in the settle window must NOT hand
     * back the stale one.
     */
    @Test
    fun `a stale but more accurate older sample is not returned as current`() {
        val tracker = CrownFixTracker()
        // Very accurate but about to age out of the freshness window.
        tracker.record(CrownFix(57.5, 12.0, base + 39_000, accuracyMeters = 5.0))
        // Less accurate, but fresh, and within a settle window of the stale one.
        tracker.record(CrownFix(57.5, 12.0, base + 45_000, accuracyMeters = 50.0))

        // now: latest (base+45s) is 55 s old = fresh; the 5 m sample (base+39s) is
        // 61 s old = stale. The stale accurate sample must be rejected.
        val now = base + 100_000
        val best = tracker.bestRecent(now)!!
        assertEquals(
            "a stale older sample must not win on accuracy",
            50.0,
            best.accuracyMeters!!,
            0.0,
        )
    }

    /**
     * The accuracy preference must never STRAND a usable pair: if the most-accurate
     * fresh sample has no partner but another fresh current does, the tracker must
     * still find the valid pair rather than leaving Collect stuck in "confirming".
     * The round-5 regression this whole selection was re-designed around.
     */
    @Test
    fun `a valid pair is never stranded by preferring an accurate but partnerless current`() {
        val tracker = CrownFixTracker()
        // The MORE accurate fix is the OLDEST — it has nothing older to pair with.
        tracker.record(CrownFix(57.5, 12.0, base + 0, accuracyMeters = 5.0))
        // The newer, less accurate fix DOES have a partner (the t0 fix).
        tracker.record(CrownFix(57.5, 12.0, base + 6_000, accuracyMeters = 50.0))

        val pair = tracker.proofPair(base + 6_000)
        assertNotNull("the achievable pair must not be stranded", pair)
        assertEquals(
            "current must be the fix that yields a valid pair, not the partnerless accurate one",
            base + 6_000,
            pair!!.current.recordedAtMillis,
        )
        assertEquals(base + 0, pair.previous.recordedAtMillis)
    }

    // ---- The whole selection, as one matrix ------------------------------

    /**
     * `proofPair` / `bestRecent` / readiness across every case that has bitten this
     * function, asserted together so no future edge case regresses one while fixing
     * another. Each row lists the recorded fixes (offset ms, accuracy), the wall
     * clock, and what the selection must produce.
     */
    @Test
    fun theSelectionMatrix() {
        data class Fx(val offsetMs: Long, val acc: Double?)
        data class Case(
            val name: String,
            val fixes: List<Fx>,
            val nowMs: Long,
            val expectReady: Boolean,
            // Expected "current" offset for the popup (pair.current when ready,
            // else the fresh distance fix), or null for "no current position".
            val expectCurrentOffsetMs: Long?,
        )

        val cases =
            listOf(
                // 1. Stale latest (minutes old) — no trustworthy current, not ready.
                Case("stale latest", listOf(Fx(0, null), Fx(6_000, null)), 200_000, false, null),
                // 2. One fresh fix — a current for distance, but no partner yet.
                Case("single fresh fix", listOf(Fx(0, null)), 0, false, 0),
                // 3. Fresh latest WITH a partner, plus an older MORE-accurate fix
                //    that has NO partner — must NOT strand the pair.
                Case(
                    "accurate-but-partnerless older fix",
                    listOf(Fx(0, 5.0), Fx(6_000, 50.0)),
                    6_000,
                    true,
                    6_000,
                ),
                // 4. Fresh latest, older best-accuracy sample is STALE — usable pair
                //    (stale fix is only the PARTNER), current is the fresh fix.
                Case(
                    "older accurate sample is stale",
                    listOf(Fx(39_000, 5.0), Fx(45_000, 50.0)),
                    100_000,
                    true,
                    45_000,
                ),
                // 5. Steady state — two fresh fixes >= MIN_DWELL apart, ready.
                Case(
                    "steady state",
                    listOf(Fx(0, 10.0), Fx(5_000, 8.0)),
                    5_000,
                    true,
                    5_000,
                ),
            )

        for (case in cases) {
            val tracker = CrownFixTracker()
            case.fixes.forEach {
                tracker.record(CrownFix(57.5, 12.0, base + it.offsetMs, accuracyMeters = it.acc))
            }
            val now = base + case.nowMs
            val pair = tracker.proofPair(now)
            assertEquals("${case.name}: readiness", case.expectReady, pair != null)

            val current = pair?.current ?: tracker.bestRecent(now)
            if (case.expectCurrentOffsetMs == null) {
                assertNull("${case.name}: expected no current", current)
            } else {
                assertEquals(
                    "${case.name}: current fix",
                    base + case.expectCurrentOffsetMs,
                    current!!.recordedAtMillis,
                )
            }

            if (pair != null) {
                assertTrue(
                    "${case.name}: the chosen pair must be a usable dwell proof",
                    CrownCollectGate.isDwellProofUsable(pair.previous, pair.current),
                )
            }
        }
    }

    // ── #911: the proof partner must be inside the crown geofence ───────────
    //
    // The server refuses a claim `outside_radius` when EITHER fix is outside the
    // ring, and the pre-warm poll feeds this tracker while the member is still
    // approaching — so the buffer holds approach-era fixes from farther out.
    // Pairing a dead-on current with one of those was the bug: the button looked
    // Ready, the first tap was refused, and only an app restart (which cleared the
    // session-scoped tracker) let a cold, all-in-range pair form. These pin the
    // in-range gate that makes the client's Ready match the server's acceptance.

    /** Crown at the same point the default [fix] sits on. */
    private val crownLat = 57.5
    private val crownLon = 12.0

    /**
     * A fix [metresEast] east of the crown, at [offsetSeconds]. At 57.5° latitude
     * 0.001° of longitude is ~59.8 m, so this converts a metre offset into a
     * coordinate the real haversine will read back as (approximately) that far out.
     */
    private fun eastFix(offsetSeconds: Long, metresEast: Double, accuracyMeters: Double? = null) =
        CrownFix(
            latitude = crownLat,
            longitude = crownLon + metresEast / 59_800.0,
            recordedAtMillis = base + offsetSeconds * 1000,
            accuracyMeters = accuracyMeters,
        )

    /** The production in-range gate, mirrored: plain distance vs the 75 m radius. */
    private val inRange: (CrownFix) -> Boolean = { f ->
        CrownRange.isInRange(
            CrownSpawnQuery.distanceMeters(f.latitude, f.longitude, crownLat, crownLon),
            CrownSpawnLimits.COLLECT_RADIUS_METERS,
        )
    }

    @Test
    fun `an out-of-range earlier fix is not a valid proof partner`() {
        val tracker = CrownFixTracker()
        // Approach-era sample ~180 m out, then the member stops on the crown.
        tracker.record(eastFix(0, metresEast = 180.0))
        tracker.record(eastFix(5, metresEast = 0.0))
        val now = base + 5_000

        // Ungated, the stale out-of-range partner pairs with the dead-on current —
        // this is exactly the false Ready that produced the server `outside_radius`.
        assertNotNull("regression: ungated selection pairs the out-of-range fix", tracker.proofPair(now))

        // Gated on the crown geofence, there is no valid partner yet, so the gate
        // honestly withholds Ready instead of letting the first tap be refused.
        assertNull("the out-of-range earlier fix must not be a partner", tracker.proofPair(now, inRange))
        assertNull(tracker.proofPartner(now, inRange))
    }

    @Test
    fun `an in-range pair collects on the first attempt without a cold start`() {
        val tracker = CrownFixTracker()
        // Both fixes are inside the ring (the member stood still in range).
        tracker.record(eastFix(0, metresEast = 20.0))
        tracker.record(eastFix(5, metresEast = 10.0))
        val now = base + 5_000

        val pair = tracker.proofPair(now, inRange)
        assertNotNull("a fully in-range dwell pair must be ready", pair)
        assertTrue("current must be in range", inRange(pair!!.current))
        assertTrue("previous must be in range", inRange(pair.previous))
        assertTrue(CrownCollectGate.isDwellProofUsable(pair.previous, pair.current))
        assertEquals(0, tracker.secondsUntilProofReady(now, inRange))
    }

    @Test
    fun `a warm in-range pair survives a popup reopen`() {
        val tracker = CrownFixTracker()
        // The tracker is session-scoped (not keyed to the popup), so a reopen re-
        // queries the SAME warm history. Prove the in-range pair is still there a
        // few seconds later with no further recording — the popup does not need a
        // cold restart to re-earn it.
        tracker.record(eastFix(0, metresEast = 15.0))
        tracker.record(eastFix(5, metresEast = 5.0))

        assertNotNull(tracker.proofPair(base + 5_000, inRange))
        // "Reopen" a couple of seconds later — still fresh (< 60 s), no new fix.
        val reopened = tracker.proofPair(base + 7_000, inRange)
        assertNotNull("the in-range pair must survive a popup close/reopen", reopened)
        assertTrue(inRange(reopened!!.previous))
    }

    @Test
    fun `the confirming countdown ignores an out-of-range approach fix`() {
        val tracker = CrownFixTracker()
        // Only an out-of-range approach fix and a fresh in-range current: there is
        // no valid in-range partner, so the countdown must stay above zero (honest
        // "confirming") rather than reading the out-of-range fix as ready.
        tracker.record(eastFix(0, metresEast = 200.0))
        tracker.record(eastFix(5, metresEast = 0.0))
        val now = base + 5_000

        assertEquals("ungated hint would falsely say ready", 0, tracker.secondsUntilProofReady(now))
        assertTrue(
            "the in-range countdown must not drop to 0 off a stale out-of-range fix",
            tracker.secondsUntilProofReady(now, inRange) > 0,
        )
    }
}
