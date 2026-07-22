package com.kungsbackacarcommunity.app.incidents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure "is this settled camera worth a fresh listNearby?" rule. The camera
 * read + the debounce are device-side; the decision — meaningful move fires,
 * jitter doesn't — is pinned here.
 */
class CameraRequeryDecisionTest {
    private val center = QueryAnchor(latitude = 57.5, longitude = 12.0, radiusMeters = 4_000.0)

    @Test
    fun `the first query always fires`() {
        assertTrue(CameraRequeryDecision.shouldRequery(last = null, next = center))
    }

    @Test
    fun `a settle at the same spot and zoom does not fire`() {
        assertFalse(CameraRequeryDecision.shouldRequery(last = center, next = center))
    }

    @Test
    fun `a tiny jitter well inside the radius does not fire`() {
        // ~11 m north — a fraction of a percent of the 4 km radius.
        val jitter = center.copy(latitude = center.latitude + 0.0001)
        assertFalse(CameraRequeryDecision.shouldRequery(last = center, next = jitter))
    }

    @Test
    fun `a pan beyond a quarter of the radius fires`() {
        // The move threshold is 25% of 4 km = 1 km. ~0.02° lat ≈ 2.2 km north.
        val panned = center.copy(latitude = center.latitude + 0.02)
        val moved =
            ViewportRadius.haversineMeters(
                center.latitude, center.longitude, panned.latitude, panned.longitude,
            )
        assertTrue("precondition: move exceeds the threshold", moved > CameraRequeryDecision.MOVE_FRACTION * center.radiusMeters)
        assertTrue(CameraRequeryDecision.shouldRequery(last = center, next = panned))
    }

    @Test
    fun `a pan just under a quarter of the radius does not fire`() {
        // ~0.005° lat ≈ 556 m — under the 1 km threshold, same zoom.
        val nudged = center.copy(latitude = center.latitude + 0.005)
        val moved =
            ViewportRadius.haversineMeters(
                center.latitude, center.longitude, nudged.latitude, nudged.longitude,
            )
        assertTrue("precondition: move stays under the threshold", moved < CameraRequeryDecision.MOVE_FRACTION * center.radiusMeters)
        assertFalse(CameraRequeryDecision.shouldRequery(last = center, next = nudged))
    }

    @Test
    fun `a material zoom-in fires even at the same centre`() {
        // Radius halves (zoom in): a 50% change is well past the 25% threshold.
        val zoomedIn = center.copy(radiusMeters = center.radiusMeters / 2.0)
        assertTrue(CameraRequeryDecision.shouldRequery(last = center, next = zoomedIn))
    }

    @Test
    fun `a material zoom-out fires even at the same centre`() {
        val zoomedOut = center.copy(radiusMeters = center.radiusMeters * 2.0)
        assertTrue(CameraRequeryDecision.shouldRequery(last = center, next = zoomedOut))
    }

    @Test
    fun `a tiny zoom nudge does not fire`() {
        // +10% radius, under the 25% zoom threshold, same centre.
        val nudged = center.copy(radiusMeters = center.radiusMeters * 1.1)
        assertFalse(CameraRequeryDecision.shouldRequery(last = center, next = nudged))
    }

    @Test
    fun `a degenerate zero prior radius refetches rather than dividing by it`() {
        val zero = center.copy(radiusMeters = 0.0)
        assertTrue(CameraRequeryDecision.shouldRequery(last = zero, next = center))
    }
}
