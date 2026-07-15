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
 *   ([onGestureBegin]). No idle timer runs while the user is actively
 *   interacting; the [IDLE_RETURN_MS] countdown is armed only once ALL gestures
 *   have ended ([onGestureEnd] returns true), so it measures time since the LAST
 *   gesture ended — a continuous pan/zoom never snaps the camera mid-gesture.
 * - If that quiet window elapses with no further gesture ([onIdleElapsed]) — or
 *   the user taps the my-location control ([onRecenterRequested]) — following
 *   RESUMES. Whether the camera actually glides back is gated by [shouldTrack],
 *   so it yields to an active route overlay.
 *
 * The 10-second countdown itself is a coroutine owned by the surface (it is not
 * pure); this type only owns the follow/idle *decision*, which is what the unit
 * test pins down.
 */
class CameraFollowController {

    /** Whether the camera is currently tracking the user's location. */
    var isFollowing: Boolean = true
        private set

    // Number of camera gestures currently in progress. Gestures can overlap
    // (e.g. a pan continuing into a pinch), so this is a count, not a flag: the
    // map is "idle" again only when it returns to 0.
    private var activeGestures: Int = 0

    /** Whether a camera gesture is currently in progress. */
    val isInteracting: Boolean get() = activeGestures > 0

    /**
     * A camera gesture (pan/zoom/rotate/tilt) BEGAN: stop following and mark a
     * gesture in progress. No idle timer should run while interacting, so the
     * surface cancels any pending idle-return when this is called.
     */
    fun onGestureBegin() {
        isFollowing = false
        activeGestures += 1
    }

    /**
     * A camera gesture ENDED. Returns true when ALL gestures have now ended (the
     * map is idle again) — which is when the surface arms the [IDLE_RETURN_MS]
     * timer, so the countdown starts from when the LAST gesture ended rather than
     * when one began. Overlapping gestures keep the count above 0 until the final
     * one lifts, so a pan-then-pinch sequence arms a single timer at the true end.
     */
    fun onGestureEnd(): Boolean {
        if (activeGestures > 0) activeGestures -= 1
        return activeGestures == 0
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
        activeGestures = 0
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
