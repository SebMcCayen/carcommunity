package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry.ProjectedPoint

/**
 * The pure "should this nearby live-sharer's chip be drawn this frame?" decision.
 *
 * ## Why this is not an inline bounds check in the overlay
 * A standalone ("Single") nearby sharer is drawn only while they are actually
 * inside the viewport — unlike a convoy member, an off-screen stranger gets no
 * edge arrow (see [com.kungsbackacarcommunity.app.live.NearbyLiveOverlay]). The
 * naive way to decide that is "is the SDK's projected pixel inside the screen
 * rectangle?", and it is WRONG in exactly the way a screenshot cannot show:
 *
 * The shell map is pitched by default (45°). A coordinate BEHIND a tilted camera
 * (or beyond the horizon) has no honest screen position, and Mapbox's
 * `pixelForCoordinate` folds it back into view — typically mirrored through the
 * centre, landing near the TOP of the screen. A plain rectangle test accepts that
 * folded pixel, so a sharer the owner has panned OFF the map reappears as a chip
 * stuck near the top/corner of the screen instead of vanishing. That is the exact
 * bug this function exists to prevent: an off-screen sharer must be HIDDEN, not
 * pinned to the edge.
 *
 * The fold is caught the same way the convoy overlay catches it
 * ([ConvoyEdgeGeometry.isProjectionTrustworthy]): cross-examine the projected
 * pixel's on-screen angle against the direction the sharer's compass bearing says
 * they lie, and disbelieve a projection that disagrees by more than a right angle.
 *
 * Kept as a pure function of numbers so the visibility logic — the only part that
 * is testable at all — is unit-tested rather than trusted through Compose.
 */
object NearbyChipVisibility {

    /**
     * Whether a nearby sharer's chip should be drawn this frame.
     *
     * @param projected the sharer's coordinate as the map SDK projected it into
     *   view pixels, or null when there is no map to project with. Null → hidden.
     * @param viewportWidth / [viewportHeight] the map surface size in pixels.
     * @param marginPx how far OUTSIDE the viewport a chip's centre may still sit
     *   and be drawn (half the chip, so a marker straddling the edge is not
     *   clipped away the instant its centre crosses). Expands the accepted
     *   rectangle.
     * @param expectedScreenAngle where the sharer lies, in degrees clockwise from
     *   screen-up — their geographic bearing from the camera centre minus the
     *   camera bearing (see [ConvoyEdgeGeometry.screenAngleDegrees]). Used only to
     *   detect the behind-camera projection fold.
     *
     * @return true only when the projection is finite, inside the margin-expanded
     *   viewport, AND trustworthy (not folded from behind a tilted camera). A
     *   NaN/Infinity or behind-camera projection returns false.
     */
    fun isVisible(
        projected: ProjectedPoint?,
        viewportWidth: Float,
        viewportHeight: Float,
        marginPx: Float,
        expectedScreenAngle: Double,
    ): Boolean {
        if (projected == null) return false
        // NaN fails every comparison below (so would drop out anyway) and ±Infinity
        // fails one side of the rectangle; checking finiteness explicitly says so.
        if (!projected.x.isFinite() || !projected.y.isFinite()) return false

        val insideWithMargin =
            projected.x >= -marginPx &&
                projected.y >= -marginPx &&
                projected.x <= viewportWidth + marginPx &&
                projected.y <= viewportHeight + marginPx
        if (!insideWithMargin) return false

        // The one thing the rectangle test cannot see: a point behind the tilted
        // camera folded back into view. Its pixel is on screen but its azimuth is
        // roughly opposite the sharer's true bearing, so the cross-examination
        // rejects it and the chip is (correctly) hidden.
        return ConvoyEdgeGeometry.isProjectionTrustworthy(
            point = projected,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            expectedScreenAngle = expectedScreenAngle,
        )
    }
}
