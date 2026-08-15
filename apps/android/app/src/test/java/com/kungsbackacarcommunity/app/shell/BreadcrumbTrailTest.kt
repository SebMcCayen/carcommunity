package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure rolling-1km breadcrumb buffer: distance trimming,
 * jitter rejection, and the implausible-jump reset. All off-device (MapPoint is
 * a plain lng/lat data class), so the tail's core rules are pinned without a map
 * or GPS.
 *
 * Distances are built by stepping LATITUDE: one degree of latitude is ~111.2 km
 * everywhere, so 0.001 deg ≈ 111.2 m and the expected lengths are easy to reason
 * about independent of longitude.
 */
class BreadcrumbTrailTest {

    /** ~111.2 m per 0.001 deg latitude — the step used to build predictable paths. */
    private val stepDeg = 0.001
    private val stepMeters = 111.2

    private fun pointAtStep(i: Int): MapPoint =
        MapPoint(longitude = 12.0, latitude = 57.0 + i * stepDeg)

    // --- basics ----------------------------------------------------------

    @Test
    fun `first fix is always kept`() {
        val trail = BreadcrumbTrail()
        assertTrue(trail.add(pointAtStep(0)))
        assertEquals(1, trail.size())
    }

    @Test
    fun `clear empties the tail`() {
        val trail = BreadcrumbTrail()
        trail.add(pointAtStep(0))
        trail.add(pointAtStep(1))
        assertTrue(trail.isNotEmpty())
        trail.clear()
        assertTrue(trail.isEmpty())
        assertEquals(0, trail.size())
    }

    // --- jitter ----------------------------------------------------------

    @Test
    fun `stationary jitter below the floor is dropped and cannot fill the buffer`() {
        val trail = BreadcrumbTrail(minMoveMeters = 5.0)
        trail.add(MapPoint(longitude = 12.0, latitude = 57.0))
        // 200 fixes each ~2 m from the anchor (well under the 5 m floor): a
        // parked car's GPS wander. None should be retained.
        repeat(200) { i ->
            // ~0.00002 deg ≈ 2.2 m nudges around the anchor.
            val jitter = if (i % 2 == 0) 0.00002 else -0.00002
            val changed = trail.add(MapPoint(longitude = 12.0, latitude = 57.0 + jitter))
            assertFalse("jitter fix $i must be rejected", changed)
        }
        assertEquals(1, trail.size())
    }

    @Test
    fun `a genuine move past the floor is kept`() {
        val trail = BreadcrumbTrail(minMoveMeters = 5.0)
        trail.add(pointAtStep(0))
        // ~111 m — comfortably past the floor.
        assertTrue(trail.add(pointAtStep(1)))
        assertEquals(2, trail.size())
    }

    // --- rolling window --------------------------------------------------

    @Test
    fun `a short path under the window keeps every point`() {
        val trail = BreadcrumbTrail(windowMeters = 1_000.0)
        // 5 steps ≈ 556 m total, under 1 km: nothing trimmed.
        repeat(6) { i -> trail.add(pointAtStep(i)) }
        assertEquals(6, trail.size())
    }

    @Test
    fun `the tail stays at about one kilometre as it rolls`() {
        val trail = BreadcrumbTrail(windowMeters = 1_000.0)
        // 40 steps ≈ 4.4 km driven — far past the window.
        repeat(40) { i -> trail.add(pointAtStep(i)) }
        val length = trail.lengthMeters()
        // Retained length is the shortest suffix >= 1 km, so it sits just above
        // the window (within one trimmed segment, ~111 m), never below it.
        assertTrue("tail length $length should be >= window", length >= 1_000.0)
        assertTrue("tail length $length should be ~1 km, not much more", length < 1_000.0 + stepMeters * 1.5)
        // Newest point is retained; the oldest ones were shed.
        assertEquals(pointAtStep(39), trail.points().last())
        assertTrue(trail.points().first().latitude > pointAtStep(0).latitude)
    }

    @Test
    fun `trimming never drops below two points`() {
        // A window so tiny every step exceeds it: the tail must still keep a
        // drawable two-point segment rather than collapsing to one.
        val trail = BreadcrumbTrail(windowMeters = 1.0)
        repeat(10) { i -> trail.add(pointAtStep(i)) }
        assertEquals(2, trail.size())
    }

    // --- implausible jump ------------------------------------------------

    @Test
    fun `an implausible jump resets the tail to the new point`() {
        val trail = BreadcrumbTrail(maxJumpMeters = 300.0)
        repeat(6) { i -> trail.add(pointAtStep(i)) }
        assertTrue(trail.size() > 1)
        // A fix ~5.5 km away in one step (0.05 deg lat) — a teleport / resume.
        val faraway = MapPoint(longitude = 12.0, latitude = 57.5)
        assertTrue(trail.add(faraway))
        assertEquals(1, trail.size())
        assertEquals(faraway, trail.points().single())
    }

    @Test
    fun `movement within the jump ceiling extends the tail normally`() {
        val trail = BreadcrumbTrail(maxJumpMeters = 300.0)
        trail.add(pointAtStep(0))
        // ~222 m — a plausible gap, under the 300 m ceiling: appended, not reset.
        trail.add(pointAtStep(2))
        assertEquals(2, trail.size())
    }

    // --- seed / restore (#849 follow-up) --------------------------------

    @Test
    fun `seed replaces the tail with the given points`() {
        val trail = BreadcrumbTrail()
        trail.add(pointAtStep(0))
        // A handful of already-recorded fixes (well under the 1 km window).
        trail.seed(listOf(pointAtStep(10), pointAtStep(11), pointAtStep(12)))
        assertEquals(3, trail.size())
        assertEquals(pointAtStep(10), trail.points().first())
        assertEquals(pointAtStep(12), trail.points().last())
    }

    @Test
    fun `seed trims a long resumed route down to the rolling window`() {
        val trail = BreadcrumbTrail()
        // A ~5.5 km resumed drive (50 steps × ~111 m): seeding keeps only the
        // newest ~1 km, exactly as the live tail would after driving that far.
        val points = (0..50).map { pointAtStep(it) }
        trail.seed(points)
        assertTrue(
            "retained length ${trail.lengthMeters()} should be >= ~1 km",
            trail.lengthMeters() >= 1_000.0,
        )
        assertTrue(
            "but should not keep the whole ~5.5 km",
            trail.lengthMeters() < 1_300.0,
        )
        // The NEWEST point is always retained — the tail restores to the head of
        // the drive, not its start.
        assertEquals(pointAtStep(50), trail.points().last())
    }

    @Test
    fun `seed with fewer than two points keeps them without trimming`() {
        val trail = BreadcrumbTrail()
        trail.seed(listOf(pointAtStep(3)))
        assertEquals(1, trail.size())
        assertEquals(pointAtStep(3), trail.points().single())
    }

    @Test
    fun `an empty seed clears the tail`() {
        val trail = BreadcrumbTrail()
        trail.add(pointAtStep(0))
        trail.add(pointAtStep(1))
        trail.seed(emptyList())
        assertTrue(trail.isEmpty())
    }

    @Test
    fun `the tail keeps growing normally after a seed`() {
        val trail = BreadcrumbTrail()
        trail.seed(listOf(pointAtStep(0), pointAtStep(1)))
        assertTrue(trail.add(pointAtStep(2)))
        assertEquals(3, trail.size())
        assertEquals(pointAtStep(2), trail.points().last())
    }

    // --- haversine sanity ------------------------------------------------

    @Test
    fun `haversine matches the known metres-per-degree-latitude`() {
        val d =
            BreadcrumbTrail.haversineMeters(
                MapPoint(longitude = 12.0, latitude = 57.0),
                MapPoint(longitude = 12.0, latitude = 57.001),
            )
        // 0.001 deg latitude ≈ 111.2 m; allow a small tolerance.
        assertTrue("distance $d should be ~111 m", d in 110.0..113.0)
    }
}
