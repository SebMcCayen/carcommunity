package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.*
import org.junit.Test

class MapCameraResumePolicyTest {
    private val point = MapPoint(12.0, 57.0)
    private val moved = MapPoint(12.001, 57.0)

    @Test fun `one return per visible edge and no replay of hidden position`() {
        val policy = MapCameraResumePolicy()
        policy.visibilityChanged(true, true, null)
        assertTrue(policy.takeReturn(point, true))
        assertFalse(policy.takeReturn(moved, true))
        policy.visibilityChanged(false, true, point)
        policy.visibilityChanged(true, true, point)
        assertFalse(policy.takeReturn(point, true))
        assertTrue(policy.pending)
        assertFalse(policy.takeReturn(moved, false))
        assertTrue(policy.takeReturn(moved, true))
    }

    @Test fun `manual intent and route ownership survive visibility changes`() {
        val policy = MapCameraResumePolicy()
        policy.visibilityChanged(true, false, null)
        assertFalse(policy.takeReturn(point, true))
        policy.visibilityChanged(false, false, point)
        policy.visibilityChanged(true, true, point)
        policy.cancel()
        assertFalse(policy.takeReturn(moved, true))
        policy.visibilityChanged(true, true, point)
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
        policy.visibilityChanged(false, true, moved)
        policy.visibilityChanged(true, true, moved)
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
