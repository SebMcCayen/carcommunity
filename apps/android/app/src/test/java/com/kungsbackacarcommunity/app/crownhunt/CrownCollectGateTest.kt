package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        for (broken in listOf(0.0, -10.0, Double.NaN)) {
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
