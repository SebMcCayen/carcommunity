package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the History card's route thumbnail projection — the whole of
 * [RouteThumbnail], which is why it is Compose-free. Only the `Canvas` stroke
 * itself is device-only.
 */
class RouteThumbnailTest {

    private val width = 72f
    private val height = 56f
    private val padding = 6f

    private fun project(points: List<LatLng>) =
        RouteThumbnail.project(points, width, height, padding)

    // --- Degenerate routes: the placeholder path, and the common one ---------

    @Test
    fun `no thumbnail field at all yields nothing to draw`() {
        // Every drive saved before thumbnails existed. There is no backfill, so
        // this is the permanent state for them — never a crash, never an empty
        // box: the caller draws the placeholder.
        assertTrue(RouteThumbnail.decode(null).isEmpty())
        assertTrue(RouteThumbnail.decode("").isEmpty())
        assertTrue(RouteThumbnail.decode("   ").isEmpty())
        assertNull(RouteThumbnail.pathFor(null, width, height, padding))
    }

    @Test
    fun `a single point is not a route`() {
        assertNull(project(listOf(LatLng(longitude = 12.076, latitude = 57.487))))
    }

    @Test
    fun `identical points (a parked phone) are not a route`() {
        val parked = List(20) { LatLng(longitude = 12.076, latitude = 57.487) }
        assertNull(project(parked))
    }

    @Test
    fun `a route shorter than the minimum extent is not a route`() {
        // ~10 m of GPS jitter in a driveway. Fitting it would blow the jitter
        // up to fill the box and draw a confident scribble that is not a drive.
        val jitterDegrees = 10.0 / 111_320.0
        val jitter =
            listOf(
                LatLng(longitude = 12.076, latitude = 57.487),
                LatLng(longitude = 12.076, latitude = 57.487 + jitterDegrees),
                LatLng(longitude = 12.076 + jitterDegrees, latitude = 57.487),
            )
        assertNull(project(jitter))
        // Just over the threshold IS drawn — the cutoff is a cutoff, not a
        // blanket refusal to draw short drives.
        val justOver = 30.0 / 111_320.0
        assertNotNull(
            project(
                listOf(
                    LatLng(longitude = 12.076, latitude = 57.487),
                    LatLng(longitude = 12.076, latitude = 57.487 + justOver),
                ),
            ),
        )
    }

    @Test
    fun `a degenerate drawing box yields nothing to draw`() {
        val route =
            listOf(
                LatLng(longitude = 12.076, latitude = 57.487),
                LatLng(longitude = 12.086, latitude = 57.497),
            )
        // Padding eats the whole box: no division by zero, no negative scale.
        assertNull(RouteThumbnail.project(route, 10f, 10f, 6f))
        assertNull(RouteThumbnail.project(route, 0f, 0f, 0f))
    }

    @Test
    fun `a non-finite coordinate yields nothing to draw`() {
        assertNull(
            project(
                listOf(
                    LatLng(longitude = 12.076, latitude = 57.487),
                    LatLng(longitude = Double.NaN, latitude = 57.497),
                ),
            ),
        )
    }

    // --- The cos(latitude) correction ---------------------------------------

    @Test
    fun `longitude is scaled by cos(latitude) so Swedish routes are not stretched`() {
        // A box that is 0.01 degrees on BOTH axes. In raw degrees that is a
        // square; on the ground at 57.5 N it is about half as wide as it is
        // tall, because a degree of longitude there is cos(57.5) ~ 0.537 of a
        // degree of latitude. Without the correction the route would be drawn
        // as a square — visibly, badly wrong.
        val lat = 57.487
        val square =
            listOf(
                LatLng(longitude = 12.076, latitude = lat),
                LatLng(longitude = 12.086, latitude = lat),
                LatLng(longitude = 12.086, latitude = lat + 0.01),
                LatLng(longitude = 12.076, latitude = lat + 0.01),
            )
        // A square box, so the box's own aspect cannot explain the result.
        val projected = RouteThumbnail.project(square, 100f, 100f, 0f)
        assertNotNull(projected)
        val points = projected!!
        val drawnWidth = points.maxOf { it.x } - points.minOf { it.x }
        val drawnHeight = points.maxOf { it.y } - points.minOf { it.y }
        val expectedRatio = cos(Math.toRadians(lat + 0.005))
        assertEquals(expectedRatio, (drawnWidth / drawnHeight).toDouble(), 0.01)
        // And the sanity check behind the maths: it comes out TALLER than wide.
        assertTrue(drawnHeight > drawnWidth)
    }

    // --- Aspect-preserving fit ----------------------------------------------

    @Test
    fun `a wide route fills the width and is centred vertically`() {
        // 4x wider than tall on the ground (after the longitude correction), in
        // a box that is not 4:1 — so the fit, not the box, decides the shape.
        val lat = 57.487
        val lonSpan = 0.04 / cos(Math.toRadians(lat))
        val route =
            listOf(
                LatLng(longitude = 12.0, latitude = lat),
                LatLng(longitude = 12.0 + lonSpan, latitude = lat + 0.01),
                LatLng(longitude = 12.0 + lonSpan, latitude = lat),
            )
        val points = RouteThumbnail.project(route, 100f, 100f, 10f)!!
        val drawnWidth = points.maxOf { it.x } - points.minOf { it.x }
        val drawnHeight = points.maxOf { it.y } - points.minOf { it.y }
        // Fills the padded width (80), keeps the 4:1 shape, and the leftover
        // vertical space is split evenly above and below.
        assertEquals(80.0, drawnWidth.toDouble(), 0.5)
        assertEquals(20.0, drawnHeight.toDouble(), 0.5)
        val topGap = points.minOf { it.y } - 10f
        val bottomGap = 90f - points.maxOf { it.y }
        assertEquals(topGap.toDouble(), bottomGap.toDouble(), 0.5)
    }

    @Test
    fun `a tall route fills the height and is centred horizontally`() {
        val lat = 57.487
        val lonSpan = 0.005 / cos(Math.toRadians(lat))
        val route =
            listOf(
                LatLng(longitude = 12.0, latitude = lat),
                LatLng(longitude = 12.0 + lonSpan, latitude = lat + 0.02),
            )
        val points = RouteThumbnail.project(route, 100f, 100f, 10f)!!
        val drawnHeight = points.maxOf { it.y } - points.minOf { it.y }
        assertEquals(80.0, drawnHeight.toDouble(), 0.5)
        val leftGap = points.minOf { it.x } - 10f
        val rightGap = 90f - points.maxOf { it.x }
        assertEquals(leftGap.toDouble(), rightGap.toDouble(), 0.5)
    }

    @Test
    fun `every projected point stays inside the padded box`() {
        val route =
            (0 until 64).map { i ->
                LatLng(
                    longitude = 12.076 + i * 0.001,
                    latitude = 57.487 + kotlin.math.sin(i / 8.0) * 0.004,
                )
            }
        val points = project(route)!!
        assertEquals(route.size, points.size)
        points.forEach {
            assertTrue("x=${it.x}", it.x >= padding - 0.01f && it.x <= width - padding + 0.01f)
            assertTrue("y=${it.y}", it.y >= padding - 0.01f && it.y <= height - padding + 0.01f)
        }
    }

    @Test
    fun `a perfectly straight north-south route does not divide by zero`() {
        // Zero longitude span: the horizontal axis must not constrain the fit
        // (it would be a division by zero) — it just centres.
        val route =
            listOf(
                LatLng(longitude = 12.076, latitude = 57.487),
                LatLng(longitude = 12.076, latitude = 57.497),
            )
        val points = project(route)!!
        points.forEach { assertTrue(it.x.isFinite() && it.y.isFinite()) }
        assertEquals(points[0].x.toDouble(), points[1].x.toDouble(), 0.001)
        assertEquals(width / 2.0, points[0].x.toDouble(), 0.001)
        // Fills the padded height.
        assertEquals(padding.toDouble(), points.minOf { it.y }.toDouble(), 0.01)
        assertEquals((height - padding).toDouble(), points.maxOf { it.y }.toDouble(), 0.01)
    }

    @Test
    fun `a perfectly straight east-west route does not divide by zero`() {
        val route =
            listOf(
                LatLng(longitude = 12.076, latitude = 57.487),
                LatLng(longitude = 12.116, latitude = 57.487),
            )
        val points = project(route)!!
        assertEquals(points[0].y.toDouble(), points[1].y.toDouble(), 0.001)
        assertEquals(height / 2.0, points[0].y.toDouble(), 0.001)
    }

    // --- North is up ---------------------------------------------------------

    @Test
    fun `the northernmost point is drawn at the top`() {
        // Canvas y grows downward, so this is a real chance to draw every route
        // upside down.
        val south = LatLng(longitude = 12.076, latitude = 57.487)
        val north = LatLng(longitude = 12.086, latitude = 57.497)
        val points = project(listOf(south, north))!!
        assertTrue("south should be lower on screen", points[0].y > points[1].y)
        // East is right, as well.
        assertTrue(points[1].x > points[0].x)
    }

    // --- Decoding what the backend encodes ------------------------------------

    @Test
    fun `decodes a backend-encoded thumbnail at 1e5 precision`() {
        // Encoded with the backend's algorithm at its precision (see
        // encodeForTest), so a precision or format change on either side of the
        // wire fails here rather than silently drawing every route as a dot in
        // the corner.
        val encoded = encodeForTest(
            listOf(
                LatLng(longitude = 12.0761, latitude = 57.4871),
                LatLng(longitude = 12.0812, latitude = 57.4899),
                LatLng(longitude = 12.0433, latitude = 57.4712),
            ),
        )
        val decoded = RouteThumbnail.decode(encoded)
        assertEquals(3, decoded.size)
        assertEquals(57.4871, decoded[0].latitude, 1e-5)
        assertEquals(12.0761, decoded[0].longitude, 1e-5)
        assertEquals(57.4712, decoded[2].latitude, 1e-5)
        assertEquals(12.0433, decoded[2].longitude, 1e-5)
        // And it projects to something drawable end to end.
        assertNotNull(RouteThumbnail.pathFor(encoded, width, height, padding))
    }

    /**
     * The backend's encoder, reimplemented here so the test exercises the real
     * wire format rather than a hand-typed string. Test-only: production code
     * only ever DECODES (the app never writes a thumbnail).
     */
    private fun encodeForTest(points: List<LatLng>): String {
        val out = StringBuilder()
        var previousLat = 0L
        var previousLon = 0L
        for (point in points) {
            val lat = Math.round(point.latitude * RouteThumbnail.POLYLINE_PRECISION)
            val lon = Math.round(point.longitude * RouteThumbnail.POLYLINE_PRECISION)
            encodeValue(lat - previousLat, out)
            encodeValue(lon - previousLon, out)
            previousLat = lat
            previousLon = lon
        }
        return out.toString()
    }

    private fun encodeValue(value: Long, out: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            out.append(((0x20 or (v.toInt() and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }

    /** Encodes a bare latitude with no longitude after it — a truncated pair. */
    private fun encodeLatitudeOnly(latitude: Double): String {
        val out = StringBuilder()
        encodeValue(Math.round(latitude * RouteThumbnail.POLYLINE_PRECISION), out)
        return out.toString()
    }

    @Test
    fun `a garbled polyline never crashes the card`() {
        // The decoder is fed a Firestore string field; a corrupt or truncated
        // value must degrade to the placeholder, not take the list down.
        val garbage = "not a polyline!!"
        val decoded = RouteThumbnail.decode(garbage)
        // Whatever it decodes to, projecting it is safe and finite.
        // The assertion is that nothing above threw, plus: whatever it decoded
        // to, projecting it stays finite and in-box (or refuses outright).
        val projected = RouteThumbnail.project(decoded, width, height, padding)
        projected?.forEach { assertTrue(it.x.isFinite() && it.y.isFinite()) }
    }

    // --- Truncation: the failure mode a long garbage string does NOT reach ---
    //
    // "not a polyline!!" is long enough that the varint decoder always finds
    // another character to read, so it exercises "nonsense in, nonsense out"
    // and never the read-past-the-end path. A value truncated MID-PAIR is a
    // different code path: the decoder consumes a latitude, reaches the end of
    // the string, and then unconditionally reads the first character of the
    // longitude that is not there. Those are the cases below, and they are the
    // realistic ones — a partially written or clipped Firestore string field.
    //
    // This is decoded while COMPOSING a row in the History LIST, so an escaping
    // throw does not blank one card, it takes down the whole History screen for
    // anyone with a single corrupt document.

    @Test
    fun `a one-character polyline degrades to the placeholder`() {
        // Shortest possible truncation: enough to start a latitude, nothing
        // left for the longitude.
        assertTrue(RouteThumbnail.decode("_").isEmpty())
        assertNull(RouteThumbnail.pathFor("_", width, height, padding))
    }

    @Test
    fun `a polyline cut off after a complete latitude degrades to the placeholder`() {
        val latitudeOnly = encodeLatitudeOnly(57.4874)
        // Sanity: this really is a whole latitude and nothing else.
        assertTrue(latitudeOnly.length > 1)
        assertTrue(RouteThumbnail.decode(latitudeOnly).isEmpty())
        assertNull(RouteThumbnail.pathFor(latitudeOnly, width, height, padding))
    }

    @Test
    fun `a valid polyline truncated mid-pair degrades to the placeholder`() {
        // A real, previously drawable thumbnail with its trailing longitude
        // lost — the shape a clipped write leaves behind.
        val full =
            encodeForTest(
                listOf(
                    LatLng(longitude = 12.0700, latitude = 57.4874),
                    LatLng(longitude = 12.0800, latitude = 57.4900),
                ),
            )
        assertNotNull(RouteThumbnail.pathFor(full, width, height, padding))
        val truncated = full.substring(0, encodeLatitudeOnly(57.4874).length)
        assertTrue(RouteThumbnail.decode(truncated).isEmpty())
        assertNull(RouteThumbnail.pathFor(truncated, width, height, padding))
    }

    @Test
    fun `a decoded coordinate outside the globe is not drawn`() {
        // Corruption that decodes cleanly still has to be refused: "not a
        // polyline!!" yields a latitude of ~3623 degrees. Fitting it draws a
        // confident line that is not a route. Only the placeholder is honest.
        val offGlobe =
            listOf(
                LatLng(longitude = 12.070, latitude = 57.487),
                LatLng(longitude = 12.080, latitude = 3623.470),
            )
        assertNull(project(offGlobe))
        assertNull(project(listOf(LatLng(longitude = 4000.0, latitude = 57.487), LatLng(longitude = 12.08, latitude = 57.49))))
        assertNull(RouteThumbnail.pathFor("not a polyline!!", width, height, padding))
    }
}
