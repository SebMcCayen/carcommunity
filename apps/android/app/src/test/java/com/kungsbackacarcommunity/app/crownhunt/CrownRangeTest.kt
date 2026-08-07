package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure in-range decision that drives BOTH the marker colour (grey vs rarity)
 * and the popup's Collect gate. In range → coloured / enabled; out → grey /
 * disabled; unknown → out (fails closed).
 */
class CrownRangeTest {
    @Test
    fun withinRadiusIsInRange() {
        assertTrue(CrownRange.isInRange(distanceMeters = 40.0, collectRadiusMeters = 75.0))
        // Exactly on the ring counts as in range (<=, matching the gate).
        assertTrue(CrownRange.isInRange(distanceMeters = 75.0, collectRadiusMeters = 75.0))
    }

    @Test
    fun beyondRadiusIsOutOfRange() {
        assertFalse(CrownRange.isInRange(distanceMeters = 75.01, collectRadiusMeters = 75.0))
        assertFalse(CrownRange.isInRange(distanceMeters = 5000.0, collectRadiusMeters = 75.0))
    }

    @Test
    fun nullOrNonFiniteDistanceFailsClosed() {
        assertFalse(CrownRange.isInRange(distanceMeters = null, collectRadiusMeters = 75.0))
        assertFalse(CrownRange.isInRange(distanceMeters = Double.NaN, collectRadiusMeters = 75.0))
        assertFalse(
            CrownRange.isInRange(distanceMeters = Double.POSITIVE_INFINITY, collectRadiusMeters = 75.0),
        )
    }

    @Test
    fun brokenRadiusNarrowsToDefaultRatherThanWidening() {
        // An absurd stored radius resolves back to the mirrored 75 m default, so a
        // crown 200 m away is NOT suddenly collectable.
        assertFalse(CrownRange.isInRange(distanceMeters = 200.0, collectRadiusMeters = 100_000.0))
        assertTrue(CrownRange.isInRange(distanceMeters = 50.0, collectRadiusMeters = 0.0))
        // A legitimate wider (but bounded) radius is honoured.
        assertTrue(CrownRange.isInRange(distanceMeters = 200.0, collectRadiusMeters = 250.0))
    }

    @Test
    fun coordinateOverloadAgreesWithTheDistanceForm() {
        // Same point → distance 0 → in range.
        assertTrue(
            CrownRange.isInRange(
                userLat = 57.5, userLon = 12.07,
                crownLat = 57.5, crownLon = 12.07,
                collectRadiusMeters = 75.0,
            ),
        )
        // A degree of latitude is ~111 km away → far out of range.
        assertFalse(
            CrownRange.isInRange(
                userLat = 57.5, userLon = 12.07,
                crownLat = 58.5, crownLon = 12.07,
                collectRadiusMeters = 75.0,
            ),
        )
    }
}
