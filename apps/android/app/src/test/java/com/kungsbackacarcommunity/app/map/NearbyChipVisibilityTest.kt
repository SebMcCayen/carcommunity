package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry.ProjectedPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure "draw this nearby sharer's chip?" decision behind
 * [com.kungsbackacarcommunity.app.live.NearbyLiveOverlay].
 *
 * The case that matters, and the reason this exists: a sharer panned OFF a
 * pitched map is folded back into view by the SDK's projection and must be
 * HIDDEN, not pinned near the top/corner of the screen (the reported bug).
 */
class NearbyChipVisibilityTest {

    // A portrait phone-ish viewport in pixels.
    private val width = 1000f
    private val height = 2000f

    // Half a 44.dp chip at ~2.75x density — a realistic on-screen margin.
    private val margin = 60f

    @Test
    fun `a sharer projected inside the viewport and agreeing with its bearing is shown`() {
        // Straight up the screen (angle 0) and projected above the centre: agrees.
        assertTrue(
            NearbyChipVisibility.isVisible(
                projected = ProjectedPoint(500f, 300f),
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 0.0,
            ),
        )
    }

    @Test
    fun `a sharer behind a tilted camera folded back into view is hidden`() {
        // The sharer is BEHIND us (screen bearing 180), but the pitched projection
        // folded them to just above the centre where the horizon is. The pixel is
        // squarely inside the viewport, so a plain bounds check would draw it — the
        // exact "stuck near the top" bug. The bearing cross-examination hides it.
        assertFalse(
            NearbyChipVisibility.isVisible(
                projected = ProjectedPoint(500f, 200f),
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 180.0,
            ),
        )
    }

    @Test
    fun `a folded pixel landing in the top-left corner is hidden`() {
        // The owner's screenshot: chip clamped to the top-left. A point up and to
        // the left of centre reads as a screen angle around 315 (up-left); a sharer
        // whose true bearing is down-right (135) cannot honestly project there, so
        // this is a fold and must be hidden rather than pinned to the corner.
        assertFalse(
            NearbyChipVisibility.isVisible(
                projected = ProjectedPoint(0f, 0f),
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 135.0,
            ),
        )
    }

    @Test
    fun `a sharer projected far off screen is hidden`() {
        assertFalse(
            NearbyChipVisibility.isVisible(
                projected = ProjectedPoint(-5000f, 900f),
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 270.0,
            ),
        )
    }

    @Test
    fun `a sharer straddling the edge within the margin is still shown`() {
        // Centre a hair outside the left edge but within the half-chip margin: the
        // chip is only half-clipped, so it stays drawn (on-screen behaviour is kept
        // identical to before the fix). Bearing left (270) agrees with the offset.
        assertTrue(
            NearbyChipVisibility.isVisible(
                projected = ProjectedPoint(-margin + 1f, 1000f),
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 270.0,
            ),
        )
    }

    @Test
    fun `a sharer just beyond the margin is hidden`() {
        assertFalse(
            NearbyChipVisibility.isVisible(
                projected = ProjectedPoint(-margin - 1f, 1000f),
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 270.0,
            ),
        )
    }

    @Test
    fun `a NaN or infinite projection is hidden`() {
        assertFalse(
            NearbyChipVisibility.isVisible(
                projected = ProjectedPoint(Float.NaN, 500f),
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 0.0,
            ),
        )
        assertFalse(
            NearbyChipVisibility.isVisible(
                projected = ProjectedPoint(500f, Float.POSITIVE_INFINITY),
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 0.0,
            ),
        )
    }

    @Test
    fun `a null projection is hidden`() {
        assertFalse(
            NearbyChipVisibility.isVisible(
                projected = null,
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 0.0,
            ),
        )
    }

    @Test
    fun `a sharer at the viewport centre is shown whatever their bearing`() {
        // Dead centre has no meaningful screen angle; it is on screen by
        // definition and must not be culled as a fold.
        assertTrue(
            NearbyChipVisibility.isVisible(
                projected = ProjectedPoint(width / 2f, height / 2f),
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 200.0,
            ),
        )
    }
}
