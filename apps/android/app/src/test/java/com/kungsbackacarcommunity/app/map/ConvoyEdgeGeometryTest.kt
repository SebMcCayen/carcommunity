package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry.ProjectedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths that is wrong in ways a screenshot cannot show: bearings, the
 * rotation correction, the behind-a-tilted-camera projection fold, and the
 * ray/rectangle intersection that pins an arrow to the edge.
 */
class ConvoyEdgeGeometryTest {

    // Kungsbacka-ish, so the longitude/latitude scale difference is realistic
    // rather than equatorial (where a flat-plane approximation would pass).
    private val baseLat = 57.4874
    private val baseLng = 12.0757

    // ---- bearings ----------------------------------------------------------

    @Test
    fun `bearing due north is zero`() {
        val bearing =
            ConvoyEdgeGeometry.initialBearingDegrees(baseLat, baseLng, baseLat + 0.05, baseLng)
        assertEquals(0.0, bearing, 0.001)
    }

    @Test
    fun `bearing due south is 180`() {
        val bearing =
            ConvoyEdgeGeometry.initialBearingDegrees(baseLat, baseLng, baseLat - 0.05, baseLng)
        assertEquals(180.0, bearing, 0.001)
    }

    @Test
    fun `bearing to a point due east is just short of 90`() {
        // NOT exactly 90, and that is correct rather than sloppy: a great circle
        // leaving eastward at this latitude curves poleward, so the INITIAL
        // bearing to a point on the same parallel is 90 minus roughly half the
        // meridian convergence over the separation. Asserting a hard 90 here
        // would be asserting a rhumb line, which is not what a direction arrow
        // should point along.
        val bearing =
            ConvoyEdgeGeometry.initialBearingDegrees(baseLat, baseLng, baseLat, baseLng + 0.05)
        assertEquals(90.0, bearing, 0.05)
        assertTrue("should lean north of due east, was $bearing", bearing < 90.0)
    }

    @Test
    fun `bearing to a point due west is just past 270`() {
        // Mirror image of the east case, for the same reason.
        val bearing =
            ConvoyEdgeGeometry.initialBearingDegrees(baseLat, baseLng, baseLat, baseLng - 0.05)
        assertEquals(270.0, bearing, 0.05)
        assertTrue("should lean north of due west, was $bearing", bearing > 270.0)
    }

    @Test
    fun `a naive planar angle would be wrong at this latitude but the great-circle bearing is not`() {
        // Equal degree deltas north and east. On a flat plane that is exactly
        // 45 degrees; on the real sphere at 57 degrees north a degree of
        // longitude is only ~0.54 of a degree of latitude in metres, so the true
        // bearing is much closer to north. This is the test that fails if
        // somebody "simplifies" this to atan2(dLat, dLng).
        val bearing =
            ConvoyEdgeGeometry.initialBearingDegrees(
                baseLat,
                baseLng,
                baseLat + 0.05,
                baseLng + 0.05,
            )
        assertTrue("expected well under 45 degrees, was $bearing", bearing < 40.0)
        assertTrue("expected north-east-ish, was $bearing", bearing > 20.0)
    }

    @Test
    fun `bearing across the antimeridian does not wrap the long way`() {
        val bearing = ConvoyEdgeGeometry.initialBearingDegrees(60.0, 179.9, 60.0, -179.9)
        // Just east, not 270 degrees the other way round the planet.
        assertTrue("expected roughly east, was $bearing", bearing in 89.0..91.0)
    }

    @Test
    fun `identical coordinates yield a defined bearing rather than NaN`() {
        val bearing = ConvoyEdgeGeometry.initialBearingDegrees(baseLat, baseLng, baseLat, baseLng)
        assertFalse(bearing.isNaN())
    }

    @Test
    fun `distance is symmetric and plausible`() {
        val there = ConvoyEdgeGeometry.distanceMeters(baseLat, baseLng, baseLat + 0.01, baseLng)
        val back = ConvoyEdgeGeometry.distanceMeters(baseLat + 0.01, baseLng, baseLat, baseLng)
        assertEquals(there, back, 0.001)
        // 0.01 degrees of latitude is ~1.11 km anywhere on Earth.
        assertEquals(1111.0, there, 5.0)
    }

    // ---- rotation ----------------------------------------------------------

    @Test
    fun `north-up camera leaves the bearing untouched`() {
        assertEquals(42.0, ConvoyEdgeGeometry.screenAngleDegrees(42.0, 0.0), 0.0001)
    }

    @Test
    fun `rotating the camera east puts a member due north on the left of the screen`() {
        // Camera bearing 90 = east is up the screen. Someone due north of us is
        // therefore to the LEFT, i.e. 270 degrees clockwise from screen-up.
        assertEquals(270.0, ConvoyEdgeGeometry.screenAngleDegrees(0.0, 90.0), 0.0001)
    }

    @Test
    fun `course-up rotation keeps a member dead ahead pointing straight up`() {
        // Driving north-west (315) with the map course-up: a member also at 315
        // from us is straight ahead, whatever the compass says.
        assertEquals(0.0, ConvoyEdgeGeometry.screenAngleDegrees(315.0, 315.0), 0.0001)
    }

    @Test
    fun `the rotation correction wraps rather than going negative`() {
        val angle = ConvoyEdgeGeometry.screenAngleDegrees(10.0, 350.0)
        assertEquals(20.0, angle, 0.0001)
        assertTrue(angle >= 0.0)
    }

    @Test
    fun `screen angle is continuous through the wrap point`() {
        val justBefore = ConvoyEdgeGeometry.screenAngleDegrees(359.9, 0.0)
        val justAfter = ConvoyEdgeGeometry.screenAngleDegrees(0.1, 0.0)
        assertEquals(0.2, ConvoyEdgeGeometry.angleDifferenceDegrees(justBefore, justAfter), 0.0001)
    }

    // ---- pitch / projection trust ------------------------------------------

    @Test
    fun `a projection agreeing with the bearing is trusted`() {
        // Expected straight up the screen; projected above the centre. Agrees.
        val point = ProjectedPoint(x = 500f, y = 100f)
        assertTrue(
            ConvoyEdgeGeometry.isProjectionTrustworthy(point, 1000f, 2000f, expectedScreenAngle = 0.0),
        )
    }

    @Test
    fun `a point behind a tilted camera folded back into view is not trusted`() {
        // The member is BEHIND us (bearing 180 relative to the screen), but a
        // pitched projection has folded them to above the centre, where the
        // horizon is. Believing that would draw a marker in front of the driver
        // for somebody who is actually behind them.
        val folded = ProjectedPoint(x = 500f, y = 200f)
        assertFalse(
            ConvoyEdgeGeometry.isProjectionTrustworthy(
                folded,
                1000f,
                2000f,
                expectedScreenAngle = 180.0,
            ),
        )
    }

    @Test
    fun `pitch foreshortening alone does not break trust`() {
        // Under 45 degrees of pitch a point ahead compresses hard toward the
        // horizon: the radius shrinks a lot, the azimuth barely moves. That must
        // still be trusted, otherwise every member in front of a tilted camera
        // becomes an arrow.
        val squashed = ProjectedPoint(x = 520f, y = 940f)
        assertTrue(
            ConvoyEdgeGeometry.isProjectionTrustworthy(
                squashed,
                1000f,
                2000f,
                expectedScreenAngle = 0.0,
            ),
        )
    }

    @Test
    fun `a point at the viewport centre is trusted despite having no angle`() {
        assertTrue(
            ConvoyEdgeGeometry.isProjectionTrustworthy(
                ProjectedPoint(500f, 1000f),
                1000f,
                2000f,
                expectedScreenAngle = 123.0,
            ),
        )
    }

    @Test
    fun `a non-finite projection is never trusted`() {
        assertFalse(
            ConvoyEdgeGeometry.isProjectionTrustworthy(
                ProjectedPoint(Float.NaN, 10f),
                1000f,
                2000f,
                expectedScreenAngle = 0.0,
            ),
        )
        assertFalse(
            ConvoyEdgeGeometry.isProjectionTrustworthy(
                ProjectedPoint(10f, Float.POSITIVE_INFINITY),
                1000f,
                2000f,
                expectedScreenAngle = 0.0,
            ),
        )
    }

    // ---- viewport membership -----------------------------------------------

    @Test
    fun `a point well inside the viewport is inside`() {
        assertTrue(
            ConvoyEdgeGeometry.isInsideViewport(ProjectedPoint(500f, 1000f), 1000f, 2000f, 24f),
        )
    }

    @Test
    fun `the margin shrinks the viewport so a point on the very edge counts as outside`() {
        val onEdge = ProjectedPoint(x = 995f, y = 1000f)
        assertTrue(ConvoyEdgeGeometry.isInsideViewport(onEdge, 1000f, 2000f, marginPx = 0f))
        assertFalse(ConvoyEdgeGeometry.isInsideViewport(onEdge, 1000f, 2000f, marginPx = 24f))
    }

    @Test
    fun `a point far off screen is outside`() {
        assertFalse(
            ConvoyEdgeGeometry.isInsideViewport(ProjectedPoint(-4000f, 900f), 1000f, 2000f, 24f),
        )
    }

    // ---- edge projection ---------------------------------------------------

    @Test
    fun `straight up lands on the top edge at the horizontal centre`() {
        val point = ConvoyEdgeGeometry.edgePoint(0.0, 1000f, 2000f, insetPx = 40f)
        assertEquals(500f, point.x, 0.01f)
        assertEquals(40f, point.y, 0.01f)
    }

    @Test
    fun `straight down lands on the bottom edge`() {
        val point = ConvoyEdgeGeometry.edgePoint(180.0, 1000f, 2000f, insetPx = 40f)
        assertEquals(500f, point.x, 0.01f)
        assertEquals(1960f, point.y, 0.01f)
    }

    @Test
    fun `due right lands on the right edge at the vertical centre`() {
        val point = ConvoyEdgeGeometry.edgePoint(90.0, 1000f, 2000f, insetPx = 40f)
        assertEquals(960f, point.x, 0.01f)
        assertEquals(1000f, point.y, 0.01f)
    }

    @Test
    fun `due left lands on the left edge`() {
        val point = ConvoyEdgeGeometry.edgePoint(270.0, 1000f, 2000f, insetPx = 40f)
        assertEquals(40f, point.x, 0.01f)
        assertEquals(1000f, point.y, 0.01f)
    }

    @Test
    fun `a diagonal on a tall viewport hits the side wall not the corner`() {
        // 45 degrees on a 1000x2000 viewport: the ray reaches the narrow side
        // long before the far top. Getting this backwards (always solving for
        // the same axis) is the classic bug in this projection.
        val point = ConvoyEdgeGeometry.edgePoint(45.0, 1000f, 2000f, insetPx = 0f)
        assertEquals(1000f, point.x, 0.01f)
        assertEquals(500f, point.y, 0.01f)
    }

    @Test
    fun `a diagonal on a square viewport lands exactly on the corner`() {
        val point = ConvoyEdgeGeometry.edgePoint(45.0, 1000f, 1000f, insetPx = 0f)
        assertEquals(1000f, point.x, 0.01f)
        assertEquals(0f, point.y, 0.01f)
    }

    @Test
    fun `every edge point stays within the inset rectangle for a full sweep`() {
        val width = 1080f
        val height = 2160f
        val inset = 56f
        var angle = 0.0
        while (angle < 360.0) {
            val point = ConvoyEdgeGeometry.edgePoint(angle, width, height, inset)
            assertTrue("x out of bounds at $angle: ${point.x}", point.x >= inset - 0.01f)
            assertTrue("x out of bounds at $angle: ${point.x}", point.x <= width - inset + 0.01f)
            assertTrue("y out of bounds at $angle: ${point.y}", point.y >= inset - 0.01f)
            assertTrue("y out of bounds at $angle: ${point.y}", point.y <= height - inset + 0.01f)
            angle += 1.0
        }
    }

    @Test
    fun `every edge point actually touches a wall for a full sweep`() {
        // Not just inside the rectangle: ON it. An arrow floating in the middle
        // of the map is as wrong as one off the screen.
        val width = 1080f
        val height = 2160f
        val inset = 56f
        var angle = 0.0
        while (angle < 360.0) {
            val point = ConvoyEdgeGeometry.edgePoint(angle, width, height, inset)
            val onWall =
                kotlin.math.abs(point.x - inset) < 0.05f ||
                    kotlin.math.abs(point.x - (width - inset)) < 0.05f ||
                    kotlin.math.abs(point.y - inset) < 0.05f ||
                    kotlin.math.abs(point.y - (height - inset)) < 0.05f
            assertTrue("angle $angle produced an interior point $point", onWall)
            angle += 1.0
        }
    }

    @Test
    fun `a viewport too small for the inset collapses to the centre rather than inverting`() {
        val point = ConvoyEdgeGeometry.edgePoint(37.0, 60f, 60f, insetPx = 100f)
        assertEquals(30f, point.x, 0.01f)
        assertEquals(30f, point.y, 0.01f)
    }

    @Test
    fun `angles beyond a full turn behave like their normalised equivalent`() {
        val once = ConvoyEdgeGeometry.edgePoint(90.0, 1000f, 2000f, 40f)
        val again = ConvoyEdgeGeometry.edgePoint(450.0, 1000f, 2000f, 40f)
        val negative = ConvoyEdgeGeometry.edgePoint(-270.0, 1000f, 2000f, 40f)
        assertEquals(once.x, again.x, 0.01f)
        assertEquals(once.y, again.y, 0.01f)
        assertEquals(once.x, negative.x, 0.01f)
        assertEquals(once.y, negative.y, 0.01f)
    }

    // ---- angle helpers -----------------------------------------------------

    @Test
    fun `angle difference takes the short way round`() {
        assertEquals(20.0, ConvoyEdgeGeometry.angleDifferenceDegrees(350.0, 10.0), 0.0001)
        assertEquals(180.0, ConvoyEdgeGeometry.angleDifferenceDegrees(0.0, 180.0), 0.0001)
        assertEquals(0.0, ConvoyEdgeGeometry.angleDifferenceDegrees(90.0, 450.0), 0.0001)
    }

    @Test
    fun `normalisation maps negatives into a single turn`() {
        assertEquals(350.0, ConvoyEdgeGeometry.normalizeDegrees(-10.0), 0.0001)
        assertEquals(10.0, ConvoyEdgeGeometry.normalizeDegrees(370.0), 0.0001)
        assertEquals(0.0, ConvoyEdgeGeometry.normalizeDegrees(360.0), 0.0001)
    }
}
