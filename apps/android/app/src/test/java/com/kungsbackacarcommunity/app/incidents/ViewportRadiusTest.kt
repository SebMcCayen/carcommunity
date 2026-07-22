package com.kungsbackacarcommunity.app.incidents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure viewport→radius derivation + server clamp. The Mapbox visible-bounds
 * read is device-only (behind the [com.kungsbackacarcommunity.app.shell.MapSurface]
 * seam); the geometry that turns bounds into a clamped query radius is pure and
 * pinned here in the blocking unit-test job.
 */
class ViewportRadiusTest {
    // Kungsbacka-ish centre; the maths is centre-agnostic but a real latitude
    // keeps the longitude-shrink honest.
    private val centerLat = 57.5
    private val centerLon = 12.0

    @Test
    fun `haversine matches a known degree of latitude`() {
        // One degree of latitude on the IUGG mean sphere is ~111.2 km.
        val d = ViewportRadius.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 5.0)
    }

    @Test
    fun `zoomed in far below the floor clamps up to 100 m`() {
        // A ~20 m-wide street view: every corner is a few metres out, so the raw
        // half-diagonal is below the server floor and must clamp UP.
        val r =
            ViewportRadius.radiusMetersForBounds(
                centerLat,
                centerLon,
                swLat = centerLat - 0.0001,
                swLon = centerLon - 0.0001,
                neLat = centerLat + 0.0001,
                neLon = centerLon + 0.0001,
            )
        assertEquals(ViewportRadius.MIN_RADIUS_METERS, r, 0.0)
    }

    @Test
    fun `zoomed out far above the ceiling clamps down to 50 km`() {
        // A multi-degree regional view: corners are >100 km out, capped at 50 km.
        val r =
            ViewportRadius.radiusMetersForBounds(
                centerLat,
                centerLon,
                swLat = centerLat - 1.0,
                swLon = centerLon - 1.0,
                neLat = centerLat + 1.0,
                neLon = centerLon + 1.0,
            )
        assertEquals(ViewportRadius.MAX_RADIUS_METERS, r, 0.0)
    }

    @Test
    fun `a mid-zoom view returns the max corner distance, unclamped`() {
        val swLat = centerLat - 0.1
        val swLon = centerLon - 0.1
        val neLat = centerLat + 0.1
        val neLon = centerLon + 0.1
        val r =
            ViewportRadius.radiusMetersForBounds(centerLat, centerLon, swLat, swLon, neLat, neLon)

        // Between the clamps for a ~22 km-tall viewport.
        assertTrue("expected an unclamped value, was $r", r > ViewportRadius.MIN_RADIUS_METERS)
        assertTrue("expected below the ceiling, was $r", r < ViewportRadius.MAX_RADIUS_METERS)
        // It IS the greatest centre-to-corner distance.
        val maxCorner =
            listOf(
                ViewportRadius.haversineMeters(centerLat, centerLon, swLat, swLon),
                ViewportRadius.haversineMeters(centerLat, centerLon, neLat, neLon),
                ViewportRadius.haversineMeters(centerLat, centerLon, neLat, swLon),
                ViewportRadius.haversineMeters(centerLat, centerLon, swLat, neLon),
            ).max()
        assertEquals(maxCorner, r, 1e-6)
    }

    @Test
    fun `radius covers every corner of the visible area`() {
        val swLat = centerLat - 0.05
        val swLon = centerLon - 0.12
        val neLat = centerLat + 0.05
        val neLon = centerLon + 0.12
        val r =
            ViewportRadius.radiusMetersForBounds(centerLat, centerLon, swLat, swLon, neLat, neLon)
        // Half-diagonal reaches the corners: nothing on screen is outside the
        // queried circle (the "empty edges" bug is what this prevents).
        for (corner in listOf(swLat to swLon, neLat to neLon, neLat to swLon, swLat to neLon)) {
            val d = ViewportRadius.haversineMeters(centerLat, centerLon, corner.first, corner.second)
            assertTrue("corner at $d m must be within radius $r", d <= r + 1e-6)
        }
    }

    @Test
    fun `a wider viewport yields a larger radius`() {
        val small =
            ViewportRadius.radiusMetersForBounds(
                centerLat, centerLon,
                centerLat - 0.02, centerLon - 0.02,
                centerLat + 0.02, centerLon + 0.02,
            )
        val large =
            ViewportRadius.radiusMetersForBounds(
                centerLat, centerLon,
                centerLat - 0.2, centerLon - 0.2,
                centerLat + 0.2, centerLon + 0.2,
            )
        assertTrue("zooming out must grow the radius ($small !< $large)", small < large)
    }
}
