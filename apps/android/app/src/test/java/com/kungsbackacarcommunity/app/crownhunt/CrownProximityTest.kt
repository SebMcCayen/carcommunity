package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure distance -> 0..1 fill behind the popup's proximity loading bar. Empty
 * when far, full at/inside the ring, a linear climb over the approach window, and
 * fail-closed (empty) for an unknown position — exactly where the marker greys and
 * the gate stays disabled.
 */
class CrownProximityTest {
    private val eps = 1e-4f

    @Test
    fun insideOrOnTheRingIsFull() {
        assertEquals(1f, CrownProximity.proximityFraction(0.0, radiusMeters = 75.0), eps)
        assertEquals(1f, CrownProximity.proximityFraction(40.0, radiusMeters = 75.0), eps)
        // Exactly on the ring counts as full, matching CrownRange's <= rule.
        assertEquals(1f, CrownProximity.proximityFraction(75.0, radiusMeters = 75.0), eps)
    }

    @Test
    fun atOrBeyondTheApproachWindowIsEmpty() {
        // radius 75 + window 500 = 575 m is the outer edge → empty.
        assertEquals(0f, CrownProximity.proximityFraction(575.0, radiusMeters = 75.0), eps)
        assertEquals(0f, CrownProximity.proximityFraction(5000.0, radiusMeters = 75.0), eps)
    }

    @Test
    fun climbsLinearlyAcrossTheWindow() {
        // Halfway through the 500 m window (at 325 m, i.e. 250 m past the ring) → 0.5.
        assertEquals(0.5f, CrownProximity.proximityFraction(325.0, radiusMeters = 75.0), eps)
        // A quarter of the way in from the outer edge (at 450 m) → 0.25.
        assertEquals(0.25f, CrownProximity.proximityFraction(450.0, radiusMeters = 75.0), eps)
    }

    @Test
    fun fillIncreasesMonotonicallyAsDistanceShrinks() {
        val far = CrownProximity.proximityFraction(500.0, radiusMeters = 75.0)
        val mid = CrownProximity.proximityFraction(300.0, radiusMeters = 75.0)
        val near = CrownProximity.proximityFraction(120.0, radiusMeters = 75.0)
        assertTrue(far < mid)
        assertTrue(mid < near)
        assertTrue(near < 1f)
    }

    @Test
    fun unknownOrBrokenDistanceFailsClosedToEmpty() {
        assertEquals(0f, CrownProximity.proximityFraction(Double.NaN, radiusMeters = 75.0), eps)
        assertEquals(
            0f,
            CrownProximity.proximityFraction(Double.POSITIVE_INFINITY, radiusMeters = 75.0),
            eps,
        )
        // A negative reading is broken, not "past the crown" — empty, never full.
        assertEquals(0f, CrownProximity.proximityFraction(-10.0, radiusMeters = 75.0), eps)
    }

    @Test
    fun brokenRadiusNarrowsToTheDefaultRing() {
        // An absurd stored radius resolves back to 75 m, so a crown 200 m away is
        // NOT suddenly "full" — it sits partway up the bar, same as with radius 75.
        val absurd = CrownProximity.proximityFraction(200.0, radiusMeters = 100_000.0)
        val default = CrownProximity.proximityFraction(200.0, radiusMeters = 75.0)
        assertEquals(default, absurd, eps)
        assertTrue(absurd < 1f)
    }

    @Test
    fun aWiderButLegitimateRadiusFillsSooner() {
        // A legitimately wider ring (250 m) means 200 m away is already in range → full.
        assertEquals(1f, CrownProximity.proximityFraction(200.0, radiusMeters = 250.0), eps)
    }

    @Test
    fun brokenApproachWindowFallsBackToTheDefault() {
        val withZero = CrownProximity.proximityFraction(325.0, radiusMeters = 75.0, approachMeters = 0.0)
        val withNaN =
            CrownProximity.proximityFraction(325.0, radiusMeters = 75.0, approachMeters = Double.NaN)
        val withDefault = CrownProximity.proximityFraction(325.0, radiusMeters = 75.0)
        assertEquals(withDefault, withZero, eps)
        assertEquals(withDefault, withNaN, eps)
    }

    @Test
    fun resultIsAlwaysClampedToUnit() {
        // Sweep a range of distances; nothing escapes 0..1.
        for (d in -100..6000 step 37) {
            val f = CrownProximity.proximityFraction(d.toDouble(), radiusMeters = 75.0)
            assertTrue("fraction $f out of range at d=$d", f in 0f..1f)
        }
    }
}
