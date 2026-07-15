package com.kungsbackacarcommunity.app.map

/**
 * Pure, JVM-unit-testable state machine for the map's "camera follows me"
 * behaviour. Holds no Android or Mapbox types so the real map surface can drive
 * it while it stays fully testable off-device.
 *
 * The rules (from the product spec):
 * - The camera FOLLOWS the user's location: while [isFollowing], each new GPS
 *   fix re-centres the camera on the puck.
 * - A manual map gesture (pan / pinch-zoom / rotate / tilt) STOPS following
 *   ([onUserGesture]). The surface then arms a [IDLE_RETURN_MS] timer.
 * - If the timer elapses with no further gesture ([onIdleElapsed]) — or the user
 *   taps the my-location control ([onRecenterRequested]) — following RESUMES and
 *   the camera glides back to the user.
 *
 * The 10-second countdown itself is a coroutine owned by the surface (it is not
 * pure); this type only owns the follow/idle *decision*, which is what the unit
 * test pins down.
 */
class CameraFollowController {

    /** Whether the camera is currently tracking the user's location. */
    var isFollowing: Boolean = true
        private set

    /** A real user pan/zoom/rotate/tilt gesture: stop following. */
    fun onUserGesture() {
        isFollowing = false
    }

    /** The idle timer elapsed with no further interaction: resume following. */
    fun onIdleElapsed() {
        isFollowing = true
    }

    /** The my-location control was tapped: resume following. */
    fun onRecenterRequested() {
        isFollowing = true
    }

    /**
     * Reset to the initial following state. Used when the underlying map view is
     * torn down and later recreated (e.g. a tab switch): a fresh map should open
     * following the user, regardless of whether the user had panned away before.
     */
    fun reset() {
        isFollowing = true
    }

    /**
     * Whether the camera should track the user's puck for the current fix: only
     * when [isFollowing] AND no route overlay is on screen. A route preview fits
     * and owns the camera, so follow must yield to it (and to any other explicit
     * camera move) rather than fighting it.
     */
    fun shouldTrack(hasRouteOverlay: Boolean): Boolean = isFollowing && !hasRouteOverlay

    companion object {
        /**
         * Idle time (ms) with no map interaction after a manual gesture before
         * the camera automatically returns to the user and resumes following.
         */
        const val IDLE_RETURN_MS: Long = 10_000L
    }
}
