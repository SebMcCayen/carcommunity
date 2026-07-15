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
        assertTrue(c.shouldTrack(hasRouteOverlay = false))
    }

    @Test
    fun `a user gesture stops following`() {
        val c = CameraFollowController()
        c.onUserGesture()
        assertFalse(c.isFollowing)
        assertFalse(c.shouldTrack(hasRouteOverlay = false))
    }

    @Test
    fun `idle timeout resumes following`() {
        val c = CameraFollowController()
        c.onUserGesture()
        c.onIdleElapsed()
        assertTrue(c.isFollowing)
        assertTrue(c.shouldTrack(hasRouteOverlay = false))
    }

    @Test
    fun `tapping recenter resumes following`() {
        val c = CameraFollowController()
        c.onUserGesture()
        c.onRecenterRequested()
        assertTrue(c.isFollowing)
    }

    @Test
    fun `reset returns to the following state`() {
        val c = CameraFollowController()
        c.onUserGesture()
        c.reset()
        assertTrue(c.isFollowing)
    }

    @Test
    fun `does not track while a route overlay owns the camera even when following`() {
        val c = CameraFollowController()
        assertTrue(c.isFollowing)
        // Following, but a route preview is on screen: yield the camera to it.
        assertFalse(c.shouldTrack(hasRouteOverlay = true))
    }

    @Test
    fun `does not track when not following regardless of route overlay`() {
        val c = CameraFollowController()
        c.onUserGesture()
        assertFalse(c.shouldTrack(hasRouteOverlay = false))
        assertFalse(c.shouldTrack(hasRouteOverlay = true))
    }

    @Test
    fun `idle return window is ten seconds`() {
        assertEquals(10_000L, CameraFollowController.IDLE_RETURN_MS)
    }
}
