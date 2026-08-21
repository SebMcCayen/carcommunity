package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the Collect button is live — the safety-critical decision in this
 * feature, pinned across the whole distance x stillness x flag matrix.
 *
 * This is here because the alternative way to check it is to drive a car past a
 * crown, which is both unrepeatable and exactly the situation the rule exists to
 * keep people out of. Every claim [CrownCollectGate]'s KDoc makes is asserted
 * below rather than narrated.
 */
class CrownCollectGateTest {

    private val radius = CrownSpawnLimits.COLLECT_RADIUS_METERS
    private val ceiling = CrownSpawnLimits.MAX_COLLECT_SPEED_MPS

    // ---- The matrix ------------------------------------------------------

    /**
     * The full 2x2x2 of {inside, outside} x {stopped, moving} x {flag on, off},
     * asserted as one table so a change to any cell has to be a deliberate edit
     * to a visible expectation rather than a quietly-passing test.
     */
    @Test
    fun collectIsEnabledOnlyWhenCloseAndStoppedAndTheFlagIsOn() {
        data class Case(
            val enabled: Boolean,
            val distance: Double,
            val speed: Double,
            val expected: Boolean,
        )

        val inside = radius - 5.0
        val outside = radius + 5.0
        val stopped = 0.0
        val moving = ceiling + 3.0

        val cases =
            listOf(
                // The ONE combination that collects.
                Case(enabled = true, distance = inside, speed = stopped, expected = true),
                // Close but rolling.
                Case(enabled = true, distance = inside, speed = moving, expected = false),
                // Stopped but too far.
                Case(enabled = true, distance = outside, speed = stopped, expected = false),
                Case(enabled = true, distance = outside, speed = moving, expected = false),
                // Flag off: nothing collects, however perfect the position.
                Case(enabled = false, distance = inside, speed = stopped, expected = false),
                Case(enabled = false, distance = inside, speed = moving, expected = false),
                Case(enabled = false, distance = outside, speed = stopped, expected = false),
                Case(enabled = false, distance = outside, speed = moving, expected = false),
            )

        for (case in cases) {
            val state =
                CrownCollectGate.evaluate(
                    featureEnabled = case.enabled,
                    distanceMeters = case.distance,
                    speedMetersPerSecond = case.speed,
                )
            assertEquals(
                "enabled=${case.enabled} distance=${case.distance} speed=${case.speed}",
                case.expected,
                CrownCollectGate.isCollectEnabled(state),
            )
        }
    }

    /** No fix at all is its own state, and it never collects. */
    @Test
    fun withNoPositionThereIsNothingToDecideAndNothingCollects() {
        val state =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = null,
                speedMetersPerSecond = 0.0,
            )
        assertEquals(CrownCollectState.NoPosition, state)
        assertFalse(CrownCollectGate.isCollectEnabled(state))
    }

    /** A NaN distance is a broken projection, not "distance zero". */
    @Test
    fun aNonFiniteDistanceIsTreatedAsNoPositionRatherThanAsBeingOnTopOfTheCrown() {
        for (broken in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val state =
                CrownCollectGate.evaluate(
                    featureEnabled = true,
                    distanceMeters = broken,
                    speedMetersPerSecond = 0.0,
                )
            assertEquals("distance=$broken", CrownCollectState.NoPosition, state)
        }
    }

    // ---- The reason shown is the REAL reason ------------------------------

    /**
     * The regression this file most exists to catch: a disabled feature must not
     * be reported as "move closer".
     *
     * Telling a member to drive towards a crown that the app is never going to
     * let them collect is both a lie and, on a road, a pointless errand. The flag
     * is therefore checked FIRST, before distance is even looked at.
     */
    @Test
    fun theFlagIsCheckedBeforeDistanceSoOffIsNeverReportedAsMoveCloser() {
        val state =
            CrownCollectGate.evaluate(
                featureEnabled = false,
                distanceMeters = 5_000.0,
                speedMetersPerSecond = 0.0,
            )
        assertEquals(CrownCollectState.FeatureOff, state)
    }

    /**
     * Distance is checked before stillness, so a crown 5 km away reads "too far"
     * rather than "stop the car" — which would be an instruction to brake on a
     * road for a crown the member is nowhere near.
     */
    @Test
    fun aDistantCrownReadsTooFarEvenAtSpeedRatherThanTellingTheDriverToStop() {
        val state =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 5_000.0,
                speedMetersPerSecond = 25.0,
            )
        assertTrue("expected TooFar, got $state", state is CrownCollectState.TooFar)
        assertEquals(5_000.0, (state as CrownCollectState.TooFar).distanceMeters, 0.001)
    }

    /**
     * [CrownCollectState.Moving] carries no speed value, and this pins that it
     * cannot start to.
     *
     * It is a `data object`: there is nowhere to put a number. That is the
     * design — a UI that could say "you are doing 9 km/h, get under 7.2" invites
     * a driver to watch the number and shave it, which is the exact behaviour the
     * stop rule exists to prevent.
     */
    @Test
    fun theMovingStateCarriesNoSpeedValueAtAll() {
        val slow =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = ceiling + 0.1,
            )
        val fast =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = 30.0,
            )
        assertEquals(CrownCollectState.Moving, slow)
        // Identical values despite a 100x speed difference — there is no number
        // in there to differ.
        assertEquals(slow, fast)
    }

    // ---- Boundaries -------------------------------------------------------

    /** Exactly on the radius is INSIDE; exactly on the ceiling is STOPPED. */
    @Test
    fun theBoundariesAreInclusiveOnTheGenerousSide() {
        val onRadius =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = radius,
                speedMetersPerSecond = 0.0,
            )
        assertEquals(CrownCollectState.Ready, onRadius)

        val onCeiling =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = ceiling,
            )
        assertEquals(CrownCollectState.Ready, onCeiling)

        val justOverCeiling =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = ceiling + 0.0001,
            )
        assertEquals(CrownCollectState.Moving, justOverCeiling)
    }

    /**
     * The crown's OWN radius wins over the mirrored constant, so a server-side
     * retune takes effect without an app release.
     */
    @Test
    fun theCrownsOwnRadiusIsUsedWhenItHasOne() {
        val tight =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 40.0,
                speedMetersPerSecond = 0.0,
                collectRadiusMeters = 25.0,
            )
        assertTrue("expected TooFar under a 25 m radius, got $tight", tight is CrownCollectState.TooFar)

        val generous =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 120.0,
                speedMetersPerSecond = 0.0,
                collectRadiusMeters = 200.0,
            )
        assertEquals(CrownCollectState.Ready, generous)
    }

    /** A malformed radius on the document falls back to the mirrored constant. */
    @Test
    fun aMalformedRadiusFallsBackToTheMirroredConstant() {
        for (broken in listOf(0.0, -10.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val inside =
                CrownCollectGate.evaluate(
                    featureEnabled = true,
                    distanceMeters = radius - 1.0,
                    speedMetersPerSecond = 0.0,
                    collectRadiusMeters = broken,
                )
            assertEquals("radius=$broken", CrownCollectState.Ready, inside)
            val outside =
                CrownCollectGate.evaluate(
                    featureEnabled = true,
                    distanceMeters = radius + 1.0,
                    speedMetersPerSecond = 0.0,
                    collectRadiusMeters = broken,
                )
            assertTrue("radius=$broken", outside is CrownCollectState.TooFar)
        }
    }

    /**
     * An ABSURD radius narrows back to 75 m rather than widening the geofence.
     *
     * The one corruption that fails open: a document claiming a 1000 km radius
     * would otherwise enable Collect from another county, and the popup would
     * print that number as if it meant something. The bound is the backend's,
     * mirrored, so client and server refuse the same crowns.
     */
    @Test
    fun anAbsurdRadiusNarrowsBackToTheMirroredConstant() {
        val justInsideTheBound =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = CrownSpawnLimits.MAX_STORED_COLLECT_RADIUS_METERS - 1.0,
                speedMetersPerSecond = 0.0,
                collectRadiusMeters = CrownSpawnLimits.MAX_STORED_COLLECT_RADIUS_METERS,
            )
        assertEquals(CrownCollectState.Ready, justInsideTheBound)

        val absurdRadii =
            listOf(CrownSpawnLimits.MAX_STORED_COLLECT_RADIUS_METERS + 0.5, 1_000_000.0)
        for (absurd in absurdRadii) {
            val farButInsideTheAbsurdRadius =
                CrownCollectGate.evaluate(
                    featureEnabled = true,
                    distanceMeters = radius + 1.0,
                    speedMetersPerSecond = 0.0,
                    collectRadiusMeters = absurd,
                )
            assertTrue(
                "radius=$absurd should not widen the gate, got $farButInsideTheAbsurdRadius",
                farButInsideTheAbsurdRadius is CrownCollectState.TooFar,
            )
        }
    }

    // ---- The resolver the parse boundary uses ----------------------------

    /**
     * [CrownSpawnLimits.resolveCollectRadiusMeters] mirrors the backend's
     * `resolveCollectRadiusMeters` exactly, so the number the popup PRINTS and
     * the number the gate USES are the same one.
     *
     * The bug this pins: a document with `collectRadiusMeters: 0` used to reach
     * the popup raw ("get within 0 m") while the gate silently substituted 75 m,
     * so the app stated one rule and enforced another.
     */
    @Test
    fun theResolverKeepsOnlyRealStoredRadii() {
        // Kept as-is.
        assertEquals(25.0, CrownSpawnLimits.resolveCollectRadiusMeters(25.0), 0.0)
        assertEquals(
            CrownSpawnLimits.MAX_STORED_COLLECT_RADIUS_METERS,
            CrownSpawnLimits.resolveCollectRadiusMeters(
                CrownSpawnLimits.MAX_STORED_COLLECT_RADIUS_METERS,
            ),
            0.0,
        )
        // Everything a wrong document can hold falls back.
        val rejected =
            listOf(
                null,
                0.0,
                -1.0,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                CrownSpawnLimits.MAX_STORED_COLLECT_RADIUS_METERS + 0.5,
            )
        for (stored in rejected) {
            assertEquals(
                "stored=$stored",
                CrownSpawnLimits.COLLECT_RADIUS_METERS,
                CrownSpawnLimits.resolveCollectRadiusMeters(stored),
                0.0,
            )
        }
    }

    // ---- Unknown speed ----------------------------------------------------

    /**
     * An unknown speed defers to the server rather than locking the member out.
     *
     * Some devices simply never populate `Location.speed`. If null meant "assume
     * moving", the feature would be permanently broken for those users with no
     * way for them to tell why. Null means "no information" — exactly as the
     * backend treats it — and the claim then meets the check that cannot be
     * fooled: a speed the SERVER derives from the two fixes.
     */
    @Test
    fun anUnknownOrBrokenSpeedDefersToTheServerInsteadOfBlocking() {
        for (unknown in listOf(null, Double.NaN, -1.0)) {
            val state =
                CrownCollectGate.evaluate(
                    featureEnabled = true,
                    distanceMeters = 10.0,
                    speedMetersPerSecond = unknown,
                )
            assertEquals("speed=$unknown", CrownCollectState.Ready, state)
            assertFalse("speed=$unknown", CrownCollectGate.isMoving(unknown))
        }
    }

    // ---- The confirming step ---------------------------------------------

    /**
     * In range and stopped but with no two-fix proof yet is [CrownCollectState.Confirming],
     * NOT [CrownCollectState.Ready] — the honest, disabled "hold on a moment" that
     * replaces a button that looked live and then refused with NeedsPosition.
     */
    @Test
    fun inRangeAndStoppedButWithoutADwellProofIsConfirmingNotReady() {
        val state =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = false,
                dwellSecondsRemaining = 2,
            )
        assertEquals(CrownCollectState.Confirming(2), state)
        assertFalse(CrownCollectGate.isCollectEnabled(state))
    }

    /**
     * #911 end-to-end: a dead-on CURRENT fix (0–10 m, tight accuracy) is still
     * Confirming — never Ready — while the two-fix proof is withheld.
     *
     * This is the shape the #911 telemetry logged: a perfect current fix, yet the
     * server refused `outside_radius` because the PRE-WARMED partner was an
     * approach-era fix outside the ring. The tracker's in-range gate now hands the
     * caller a null partner in that case, so `dwellProofReady` is false and the
     * gate keeps the button honestly disabled instead of letting the first tap be
     * refused — no app restart required.
     */
    @Test
    fun aPerfectCurrentFixWithNoInRangePartnerIsConfirmingNotReady() {
        val state =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 4.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = false,
                dwellSecondsRemaining = 3,
                accuracyMeters = 6.0,
            )
        assertEquals(CrownCollectState.Confirming(3), state)
        assertFalse(CrownCollectGate.isCollectEnabled(state))
    }

    /** Once the proof is ready the same position collects. */
    @Test
    fun onceTheDwellProofIsReadyTheButtonGoesLive() {
        val state =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = true,
            )
        assertEquals(CrownCollectState.Ready, state)
        assertTrue(CrownCollectGate.isCollectEnabled(state))
    }

    /**
     * A KNOWN accuracy coarser than the collect radius is [CrownCollectState.WaitingForSignal],
     * NOT [CrownCollectState.Confirming]: the distance cannot be trusted yet, so wait
     * for GPS to settle rather than sending a pair one bad sample would fail as
     * outside_radius. It is its OWN state so the reason shown is GPS, not stillness,
     * and it never collects.
     */
    @Test
    fun aPositionTooCoarseToTrustIsWaitingForSignalRatherThanReady() {
        val state =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = true,
                accuracyMeters = radius + 20.0,
            )
        assertEquals(CrownCollectState.WaitingForSignal, state)
        assertFalse(CrownCollectGate.isCollectEnabled(state))
    }

    /**
     * Coarse GPS is [CrownCollectState.WaitingForSignal] even BEFORE the dwell proof
     * has aged in: the accuracy check runs first, so a member with a fuzzy fix and
     * no partner yet is told the real (GPS) hold-up, not a dwell countdown they
     * cannot act on. The distinct-state split is what makes this unambiguous.
     */
    @Test
    fun coarseGpsIsWaitingForSignalEvenWhenTheDwellProofIsAlsoNotReady() {
        val state =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = false,
                dwellSecondsRemaining = 3,
                accuracyMeters = radius + 40.0,
            )
        assertEquals(CrownCollectState.WaitingForSignal, state)
        assertFalse(CrownCollectGate.isCollectEnabled(state))
    }

    /**
     * The live fix, end to end at the logic level: a coarse fix reads
     * WaitingForSignal, and the SAME position with an improved (fine) accuracy —
     * the dwell already satisfied — flips to Ready, the button enabling itself. No
     * close/reopen: only the accuracy the gate is fed changed.
     */
    @Test
    fun anAccuracyImprovementFlipsWaitingForSignalStraightToReady() {
        fun stateAt(accuracy: Double) =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = true,
                accuracyMeters = accuracy,
            )

        val coarse = stateAt(radius + 30.0)
        assertEquals(CrownCollectState.WaitingForSignal, coarse)
        assertFalse(CrownCollectGate.isCollectEnabled(coarse))

        val fine = stateAt(radius / 5.0)
        assertEquals(CrownCollectState.Ready, fine)
        assertTrue(CrownCollectGate.isCollectEnabled(fine))
    }

    /**
     * With the coarse-GPS wait now its OWN state, [CrownCollectState.Confirming] is
     * only ever the DWELL wait — which always has a countable answer. So a
     * Confirming reached with a settled (fine) position ALWAYS carries its positive
     * seconds hint: the null-seconds Confirming that used to swallow the countdown
     * (when GPS was the real hold-up but the dwell reported ready) can no longer
     * occur, because that case is WaitingForSignal instead.
     */
    @Test
    fun theDwellConfirmingAlwaysCarriesItsSecondsOnceGpsIsSettled() {
        val state =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = false,
                dwellSecondsRemaining = 2,
                accuracyMeters = 8.0,
            )
        assertEquals(CrownCollectState.Confirming(2), state)
        val seconds = (state as CrownCollectState.Confirming).secondsRemaining
        assertNotNull("dwell Confirming must carry a countdown", seconds)
        assertTrue("the countdown must be positive, was $seconds", seconds!! > 0)
    }

    /**
     * An UNKNOWN accuracy never blocks — exactly as an unknown speed defers to the
     * server — so a device that never reports accuracy is never locked out.
     */
    @Test
    fun anUnknownOrBrokenAccuracyDefersInsteadOfBlocking() {
        for (unknown in listOf(null, Double.NaN, -1.0)) {
            val state =
                CrownCollectGate.evaluate(
                    featureEnabled = true,
                    distanceMeters = 10.0,
                    speedMetersPerSecond = 0.0,
                    dwellProofReady = true,
                    accuracyMeters = unknown,
                )
            assertEquals("accuracy=$unknown", CrownCollectState.Ready, state)
            assertFalse("accuracy=$unknown", CrownCollectGate.isPositionUnsettled(unknown, radius))
        }
    }

    /**
     * Distance and stillness are still judged BEFORE the confirming step: a crown
     * out of range reads "too far", and a moving one reads "stop first", never
     * "confirming" — the confirming step is only reached once the member is in
     * range and stopped.
     */
    @Test
    fun confirmingNeverMasksTheRealReasonWhenTooFarOrMoving() {
        val tooFar =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = radius + 50.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = false,
            )
        assertTrue("expected TooFar, got $tooFar", tooFar is CrownCollectState.TooFar)

        val moving =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 10.0,
                speedMetersPerSecond = ceiling + 5.0,
                dwellProofReady = false,
            )
        assertEquals(CrownCollectState.Moving, moving)
    }

    /**
     * The live-update fix's pure seam: the gate must read accuracy off the
     * FRESHEST fix, not a pinned proof current.
     *
     * The popup's proof pair is chosen for best-accuracy-with-a-valid-in-range-
     * partner, so it can stay pinned to an older, coarse fix while newer, finer
     * fixes arrive — which is why the popup used to stay stuck on "confirming"
     * until a close/reopen. [CrownFixTracker.latest] always reflects the newest
     * reading, so feeding ITS accuracy to the gate flips WaitingForSignal → Ready
     * the instant the signal sharpens. This drives the tracker exactly as the
     * popup loop does and asserts the transition end to end.
     */
    @Test
    fun theFreshestFixAccuracyFlipsTheGateLiveWithoutAReopen() {
        val tracker = CrownFixTracker()
        val t0 = 1_000_000L
        val dwellMs = CrownSpawnLimits.MIN_DWELL_SECONDS * 1000
        // A warm, aged-in dwell built from COARSE fixes — the proof is ready but
        // the position is fuzzy.
        tracker.record(CrownFix(57.5, 12.0, t0, accuracyMeters = 120.0))
        tracker.record(CrownFix(57.5, 12.0, t0 + dwellMs, accuracyMeters = 120.0))

        val coarseAccuracy = tracker.latest?.accuracyMeters
        assertEquals(120.0, coarseAccuracy!!, 0.0)
        val waiting =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 5.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = true,
                accuracyMeters = coarseAccuracy,
            )
        assertEquals(CrownCollectState.WaitingForSignal, waiting)
        assertFalse(CrownCollectGate.isCollectEnabled(waiting))

        // A newer, FINER fix lands. The freshest accuracy improves...
        tracker.record(CrownFix(57.5, 12.0, t0 + dwellMs + 2_000, accuracyMeters = 8.0))
        val fineAccuracy = tracker.latest?.accuracyMeters
        assertEquals(8.0, fineAccuracy!!, 0.0)

        // ...and the gate, read off the freshest fix, is Ready — button live.
        val ready =
            CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = 5.0,
                speedMetersPerSecond = 0.0,
                dwellProofReady = true,
                accuracyMeters = fineAccuracy,
            )
        assertEquals(CrownCollectState.Ready, ready)
        assertTrue(CrownCollectGate.isCollectEnabled(ready))
    }

    /**
     * The gate must judge distance and accuracy off the SAME fix.
     *
     * The popup derives one `crownGateFix` and reads distance, speed AND accuracy
     * off it, so the button can never trust a distance computed from one fix
     * against the accuracy of another (which could enable Collect on a fuzzy
     * position, or refuse a clean one). This mirrors that call site: distance is
     * computed from each fix's OWN coordinates and handed to evaluate together with
     * that same fix's accuracy, and an improving fix flips the state live.
     */
    @Test
    fun distanceAndAccuracyAreJudgedFromTheSameFixSoAnImprovingFixFlipsLive() {
        val crownLat = 57.5
        val crownLon = 12.0
        fun stateFrom(fix: CrownFix): CrownCollectState {
            val distance =
                CrownSpawnQuery.distanceMeters(
                    fix.latitude,
                    fix.longitude,
                    crownLat,
                    crownLon,
                )
            return CrownCollectGate.evaluate(
                featureEnabled = true,
                distanceMeters = distance,
                speedMetersPerSecond = fix.speedMetersPerSecond,
                dwellProofReady = true,
                accuracyMeters = fix.accuracyMeters,
            )
        }

        // Right on the crown, stopped, but a fuzzy fix.
        val coarse =
            CrownFix(
                crownLat,
                crownLon,
                1_000_000L,
                speedMetersPerSecond = 0.0,
                accuracyMeters = radius + 25.0,
            )
        assertEquals(CrownCollectState.WaitingForSignal, stateFrom(coarse))

        // The same position as a FINE fix — distance and accuracy both off it.
        val fine =
            CrownFix(
                crownLat,
                crownLon,
                1_000_002L,
                speedMetersPerSecond = 0.0,
                accuracyMeters = 6.0,
            )
        val ready = stateFrom(fine)
        assertEquals(CrownCollectState.Ready, ready)
        assertTrue(CrownCollectGate.isCollectEnabled(ready))
    }

    // ---- The dwell proof --------------------------------------------------

    @Test
    fun aProofPairMustSpanTheServersDwellWindowInOrder() {
        fun fix(atMillis: Long) = CrownFix(57.5, 12.0, atMillis)
        val now = 1_000_000L
        val minMs = CrownSpawnLimits.MIN_DWELL_SECONDS * 1000
        val maxMs = CrownSpawnLimits.MAX_DWELL_SECONDS * 1000

        // Too tight — the earlier fix says nothing yet.
        assertFalse(
            CrownCollectGate.isDwellProofUsable(fix(now - minMs + 1), fix(now)),
        )
        // Exactly the minimum is enough.
        assertTrue(CrownCollectGate.isDwellProofUsable(fix(now - minMs), fix(now)))
        // Exactly the maximum is still enough.
        assertTrue(CrownCollectGate.isDwellProofUsable(fix(now - maxMs), fix(now)))
        // Beyond it the earlier fix no longer describes "now".
        assertFalse(CrownCollectGate.isDwellProofUsable(fix(now - maxMs - 1), fix(now)))
        // Backwards in time is malformed, never "a very long dwell".
        assertFalse(CrownCollectGate.isDwellProofUsable(fix(now), fix(now - minMs)))
        // Two readings of the same instant prove nothing.
        assertFalse(CrownCollectGate.isDwellProofUsable(fix(now), fix(now)))
    }
}
