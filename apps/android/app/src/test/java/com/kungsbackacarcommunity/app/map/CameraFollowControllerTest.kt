package com.kungsbackacarcommunity.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFollowControllerTest {

    @Test
    fun `follows by default`() {
        val c = CameraFollowController()
        assertTrue(c.isFollowing)
        assertFalse(c.isInteracting)
        assertTrue(c.shouldTrack(hasRouteOverlay = false))
    }

    @Test
    fun `a gesture beginning stops following and marks interacting`() {
        val c = CameraFollowController()
        c.onGestureBegin()
        assertFalse(c.isFollowing)
        assertTrue(c.isInteracting)
        assertFalse(c.shouldTrack(hasRouteOverlay = false))
    }

    @Test
    fun `single gesture end reports idle so the timer arms`() {
        val c = CameraFollowController()
        c.onGestureBegin()
        assertTrue("all gestures ended -> arm timer", c.onGestureEnd())
        assertFalse(c.isInteracting)
    }

    @Test
    fun `overlapping gestures only report idle when the last one ends`() {
        val c = CameraFollowController()
        // Pan begins, then a pinch begins before the pan lifts.
        c.onGestureBegin()
        c.onGestureBegin()
        // First gesture ends: still interacting, do NOT arm the timer yet.
        assertFalse("still interacting -> no timer", c.onGestureEnd())
        assertTrue(c.isInteracting)
        // Last gesture ends: now idle -> arm the timer.
        assertTrue("last gesture ended -> arm timer", c.onGestureEnd())
        assertFalse(c.isInteracting)
    }

    @Test
    fun `a gesture end without a begin never underflows`() {
        val c = CameraFollowController()
        // Defensive: an unmatched end must report idle and not go negative.
        assertTrue(c.onGestureEnd())
        assertFalse(c.isInteracting)
    }

    @Test
    fun `idle timeout resumes following`() {
        val c = CameraFollowController()
        c.onGestureBegin()
        c.onGestureEnd()
        c.onIdleElapsed()
        assertTrue(c.isFollowing)
        assertTrue(c.shouldTrack(hasRouteOverlay = false))
    }

    @Test
    fun `idle timeout resumes state but does not track while a route overlay is active`() {
        val c = CameraFollowController()
        c.onGestureBegin()
        c.onGestureEnd()
        c.onIdleElapsed()
        // Follow STATE is resumed...
        assertTrue(c.isFollowing)
        // ...but the camera must NOT glide back while a route overlay owns it.
        assertFalse(c.shouldTrack(hasRouteOverlay = true))
        // Once the overlay clears, follow resumes moving the camera.
        assertTrue(c.shouldTrack(hasRouteOverlay = false))
    }

    @Test
    fun `tapping recenter resumes following`() {
        val c = CameraFollowController()
        c.onGestureBegin()
        c.onGestureEnd()
        c.onRecenterRequested()
        assertTrue(c.isFollowing)
    }

    @Test
    fun `reset returns to the following idle state`() {
        val c = CameraFollowController()
        c.onGestureBegin()
        c.reset()
        assertTrue(c.isFollowing)
        assertFalse(c.isInteracting)
    }

    @Test
    fun `does not track while a route overlay owns the camera even when following`() {
        val c = CameraFollowController()
        assertTrue(c.isFollowing)
        assertFalse(c.shouldTrack(hasRouteOverlay = true))
    }

    @Test
    fun `does not track when not following regardless of route overlay`() {
        val c = CameraFollowController()
        c.onGestureBegin()
        assertFalse(c.shouldTrack(hasRouteOverlay = false))
        assertFalse(c.shouldTrack(hasRouteOverlay = true))
    }

    @Test
    fun `idle return window is ten seconds`() {
        assertEquals(10_000L, CameraFollowController.IDLE_RETURN_MS)
    }
}
