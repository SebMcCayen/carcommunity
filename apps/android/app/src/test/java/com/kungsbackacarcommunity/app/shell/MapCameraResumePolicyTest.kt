package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.*
import org.junit.Test

class MapCameraResumePolicyTest {
    private val point = MapPoint(12.0, 57.0)
    private val moved = MapPoint(12.001, 57.0)

    @Test fun `one return per visible edge and no replay of hidden position`() {
        val policy = MapCameraResumePolicy()
        policy.visibilityChanged(true, true, null)
        assertTrue(policy.takeReturn(1L, true))
        assertFalse(policy.takeReturn(2L, true))
        policy.visibilityChanged(false, true, 1L)
        policy.visibilityChanged(true, true, 1L)
        assertFalse(policy.takeReturn(1L, true))
        assertTrue(policy.pending)
        assertFalse(policy.takeReturn(2L, false))
        assertTrue(policy.takeReturn(2L, true))
    }

    @Test fun `fresh identical coordinate resumes but stale and cached sources never do`() {
        val policy = MapCameraResumePolicy()
        policy.visibilityChanged(true, true, null)
        policy.position(point, 1_000, 0.0, 1L)
        assertTrue(policy.takeReturn(1L, true))
        policy.visibilityChanged(false, true, 1L)
        policy.visibilityChanged(true, true, 1L)
        policy.position(point, 2_000, 0.0, 1L) // Cached identical position.
        assertFalse(policy.takeReturn(1L, true))
        policy.position(moved, 2_000, 0.0, 1L) // Changed coordinates do not make a cached receipt new.
        assertFalse(policy.takeReturn(1L, true))
        assertFalse(policy.takeReturn(2L, false)) // Stale identical source.
        assertFalse(policy.takeReturn(3L, false)) // Stale changed source.
        policy.position(point, 3_000, 0.0, 2L)
        assertTrue(policy.takeReturn(2L, true)) // Fresh identical position must not starve.
        assertFalse(policy.pending)
    }

    @Test fun `SDK fallback can return once but interpolated frames cannot create movement proof`() {
        val policy = MapCameraResumePolicy()
        policy.visibilityChanged(true, true, null)
        assertTrue(policy.takeReturn(null, true))
        assertFalse(policy.pending)
        policy.position(point, 1_000, sourceSequence = 1L)
        policy.position(moved, 1_500, sourceSequence = 1L)
        assertFalse(policy.acceptsCourse(1_500))
        policy.position(moved, 2_000, sourceSequence = 2L)
        assertTrue(policy.acceptsCourse(2_000))
    }

    @Test fun `manual intent and route ownership survive visibility changes`() {
        val policy = MapCameraResumePolicy()
        policy.visibilityChanged(true, false, null)
        assertFalse(policy.takeReturn(1L, true))
        policy.visibilityChanged(false, false, 1L)
        policy.visibilityChanged(true, true, 1L)
        policy.cancel()
        assertFalse(policy.takeReturn(2L, true))
        policy.visibilityChanged(true, true, 1L)
        assertFalse(policy.pending)
    }

    @Test fun `stationary course and old heading after resume are ignored`() {
        val policy = MapCameraResumePolicy()
        policy.visibilityChanged(true, true, null)
        policy.position(point, 0, 0.0)
        assertFalse(policy.acceptsCourse(0))
        policy.position(moved, 1_000, 10.0)
        assertTrue(policy.acceptsCourse(1_000))
        policy.position(moved, 1_100, 0.0)
        assertFalse(policy.acceptsCourse(1_100))
        policy.position(moved, 1_200, 10.0)
        policy.visibilityChanged(false, true, 2L)
        policy.visibilityChanged(true, true, 2L)
        assertFalse(policy.acceptsCourse(1_300))
    }

    @Test fun `unknown speed requires actual displacement and recent movement`() {
        val policy = MapCameraResumePolicy()
        policy.visibilityChanged(true, true, null)
        policy.position(point, 0)
        policy.position(MapPoint(12.000001, 57.0), 1_000)
        assertFalse(policy.acceptsCourse(1_000))
        policy.position(moved, 2_000)
        assertTrue(policy.acceptsCourse(2_000))
        assertFalse(policy.acceptsCourse(4_001))
    }

    @Test fun `rotation crosses north by shortest arc in both directions`() {
        assertEquals(0.0, MapCameraResumePolicy.bearingBetween(359.0, 1.0, 0.5), 0.0001)
        assertEquals(0.0, MapCameraResumePolicy.bearingBetween(1.0, 359.0, 0.5), 0.0001)
        assertEquals(1.0, MapCameraResumePolicy.bearingBetween(359.0, 1.0, 1.0), 0.0001)
    }
}
