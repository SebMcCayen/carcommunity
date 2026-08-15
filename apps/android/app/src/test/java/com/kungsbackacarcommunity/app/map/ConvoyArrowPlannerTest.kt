package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry.ProjectedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection rules: who gets a marker, who gets an arrow, who gets merged
 * away, and who gets dropped for being stale.
 */
class ConvoyArrowPlannerTest {

    private val cameraLat = 57.4874
    private val cameraLng = 12.0757
    private val width = 1000f
    private val height = 2000f
    private val now = 1_700_000_000_000L

    private fun member(
        uid: String,
        latitude: Double = cameraLat,
        longitude: Double = cameraLng,
        updatedAtMillis: Long? = now,
    ) = ConvoyMemberPosition(
        uid = uid,
        latitude = latitude,
        longitude = longitude,
        displayName = uid,
        imagePath = "vehicleImages/$uid/main.jpg",
        updatedAtMillis = updatedAtMillis,
    )

    /** Projects everything to a fixed off-screen point unless overridden. */
    private fun planWith(
        members: List<ConvoyMemberPosition>,
        cameraBearing: Double = 0.0,
        nowMillis: Long = now,
        project: (ConvoyMemberPosition) -> ProjectedPoint? = { ProjectedPoint(-5000f, -5000f) },
    ) = ConvoyArrowPlanner.plan(
        members = members,
        cameraLatitude = cameraLat,
        cameraLongitude = cameraLng,
        cameraBearing = cameraBearing,
        viewportWidth = width,
        viewportHeight = height,
        edgeInsetPx = 40f,
        nowMillis = nowMillis,
        project = project,
    )

    // ---- the arrow/marker split --------------------------------------------

    @Test
    fun `a member inside the viewport gets a marker and no arrow`() {
        val north = member("north", latitude = cameraLat + 0.001)
        val result = planWith(listOf(north)) { ProjectedPoint(500f, 800f) }
        assertEquals(1, result.onScreen.size)
        assertTrue(result.offScreen.isEmpty())
    }

    @Test
    fun `a member outside the viewport gets an arrow and no marker`() {
        val far = member("far", latitude = cameraLat + 0.5)
        val result = planWith(listOf(far))
        assertTrue(result.onScreen.isEmpty())
        assertEquals(1, result.offScreen.size)
    }

    @Test
    fun `nobody is ever both a marker and an arrow`() {
        val members =
            listOf(
                member("near", latitude = cameraLat + 0.0005),
                member("far", latitude = cameraLat + 0.5),
            )
        val result =
            planWith(members) { candidate ->
                if (candidate.uid == "near") ProjectedPoint(500f, 900f) else ProjectedPoint(500f, -9000f)
            }
        val markerUids = result.onScreen.map { it.member.uid }.toSet()
        val arrowUids = result.offScreen.map { it.member.uid }.toSet()
        assertTrue(markerUids.intersect(arrowUids).isEmpty())
        assertEquals(setOf("near"), markerUids)
        assertEquals(setOf("far"), arrowUids)
    }

    @Test
    fun `an off-screen member north of us points up the screen on a north-up map`() {
        val north = member("north", latitude = cameraLat + 0.5)
        val arrow = planWith(listOf(north)).offScreen.single()
        assertEquals(0.0, arrow.angleDegrees, 0.5)
        // Pinned to the top edge at the inset.
        assertEquals(40f, arrow.point.y, 0.5f)
    }

    @Test
    fun `rotating the map moves the arrow to the correct edge`() {
        val north = member("north", latitude = cameraLat + 0.5)
        // Camera bearing 90 (east up the screen): due north is now to the LEFT.
        val arrow = planWith(listOf(north), cameraBearing = 90.0).offScreen.single()
        assertEquals(270.0, arrow.angleDegrees, 0.5)
        assertEquals(40f, arrow.point.x, 0.5f)
    }

    @Test
    fun `a member folded into view by a pitched camera still gets an arrow`() {
        // They are due SOUTH (behind a north-up camera), but the projection
        // claims they are near the top of the screen. The bearing cross-check
        // must win, otherwise this renders as a marker ahead of the driver.
        val behind = member("behind", latitude = cameraLat - 0.5)
        val result = planWith(listOf(behind)) { ProjectedPoint(500f, 150f) }
        assertTrue(result.onScreen.isEmpty())
        val arrow = result.offScreen.single()
        assertEquals(180.0, arrow.angleDegrees, 0.5)
        // Bottom edge: behind you.
        assertEquals(height - 40f, arrow.point.y, 0.5f)
    }

    @Test
    fun `a member with no trustworthy on-screen projection gets an arrow not a drop`() {
        // A null projection now means the renderer has no HONEST on-screen position
        // for this member — behind the tilted camera / folded / clamped, which the
        // seam refuses to hand back (see MapProjection.screenPositionFor). That is
        // OFF-SCREEN, not absent: the member is ~55 km north, so they get an edge
        // arrow drawn from their bearing (which never needs the pixel), rather than
        // silently vanishing. This is the convoy half of the stuck-in-corner fix.
        val ghost = member("ghost", latitude = cameraLat + 0.5)
        val result = planWith(listOf(ghost)) { null }
        assertTrue(result.onScreen.isEmpty())
        val arrow = result.offScreen.single()
        assertEquals("ghost", arrow.member.uid)
        // Due north, north-up: arrow points up and pins to the top edge inset.
        assertEquals(0.0, arrow.angleDegrees, 0.5)
        assertEquals(40f, arrow.point.y, 0.5f)
    }

    @Test
    fun `a member under the puck with no projection gets neither marker nor arrow`() {
        // No honest pixel AND essentially at the camera centre (< MIN_ARROW_DISTANCE):
        // they are on top of you, so no edge arrow pointing at nothing — and we
        // cannot place a marker without a pixel. Neither, rather than a spurious arrow.
        val onTop = member("onTop", latitude = cameraLat, longitude = cameraLng)
        val result = planWith(listOf(onTop)) { null }
        assertTrue(result.onScreen.isEmpty())
        assertTrue(result.offScreen.isEmpty())
    }

    // ---- degenerate positions ----------------------------------------------

    @Test
    fun `a member at the exact camera centre is a marker never an arrow`() {
        val stacked = member("stacked", latitude = cameraLat, longitude = cameraLng)
        val result = planWith(listOf(stacked)) { ProjectedPoint(500f, 1000f) }
        assertEquals(listOf("stacked"), result.onScreen.map { it.member.uid })
        assertTrue(result.offScreen.isEmpty())
    }

    @Test
    fun `a member at the camera centre never becomes an arrow even if the projection lies`() {
        // Zero separation makes the bearing meaningless, so a bogus projection
        // must not be allowed to fling them to an edge pointing at nothing.
        val stacked = member("stacked")
        val result = planWith(listOf(stacked)) { ProjectedPoint(-9000f, -9000f) }
        assertTrue(result.offScreen.isEmpty())
        assertEquals(1, result.onScreen.size)
    }

    @Test
    fun `a stale position is dropped entirely`() {
        val stale =
            member(
                "stale",
                latitude = cameraLat + 0.5,
                updatedAtMillis = now - ConvoyArrowPlanner.STALE_AFTER_MS - 1,
            )
        val result = planWith(listOf(stale))
        assertTrue(result.onScreen.isEmpty())
        assertTrue(result.offScreen.isEmpty())
    }

    @Test
    fun `a position right on the staleness boundary is still shown`() {
        val edge =
            member(
                "edge",
                latitude = cameraLat + 0.5,
                updatedAtMillis = now - ConvoyArrowPlanner.STALE_AFTER_MS,
            )
        assertEquals(1, planWith(listOf(edge)).offScreen.size)
    }

    @Test
    fun `the staleness window stays above the stationary heartbeat so parked members survive`() {
        // The subtle failure mode: the stationary publish heartbeat is 3 min, so a
        // parked-but-alive member only republishes every 3 min. If STALE_AFTER_MS
        // were at or below that, they would be dropped as "stale" in the gap
        // between heartbeats. It MUST stay strictly greater. Assert the invariant so
        // the two constants (different packages) cannot drift into a regression.
        assertTrue(
            "STALE_AFTER_MS (${ConvoyArrowPlanner.STALE_AFTER_MS}) must exceed the 3-min " +
                "stationary heartbeat (${com.kungsbackacarcommunity.app.location.BackgroundLocation.STATIONARY_HEARTBEAT_MS})",
            ConvoyArrowPlanner.STALE_AFTER_MS >
                com.kungsbackacarcommunity.app.location.BackgroundLocation.STATIONARY_HEARTBEAT_MS,
        )
    }

    @Test
    fun `a member on the 3-minute heartbeat is NOT treated as stale`() {
        // Exactly one heartbeat old: still live (well inside STALE_AFTER_MS).
        val justBeat =
            member(
                "parked",
                latitude = cameraLat + 0.5,
                updatedAtMillis =
                    now - com.kungsbackacarcommunity.app.location.BackgroundLocation.STATIONARY_HEARTBEAT_MS,
            )
        assertEquals(1, planWith(listOf(justBeat)).offScreen.size)
    }

    @Test
    fun `an undated position is shown rather than assumed stale`() {
        val undated = member("undated", latitude = cameraLat + 0.5, updatedAtMillis = null)
        assertEquals(1, planWith(listOf(undated)).offScreen.size)
        assertFalse(ConvoyArrowPlanner.isStale(null, now))
    }

    @Test
    fun `a zero-size viewport plans nothing rather than dividing by it`() {
        val result =
            ConvoyArrowPlanner.plan(
                members = listOf(member("a", latitude = cameraLat + 0.5)),
                cameraLatitude = cameraLat,
                cameraLongitude = cameraLng,
                cameraBearing = 0.0,
                viewportWidth = 0f,
                viewportHeight = 0f,
                edgeInsetPx = 40f,
                nowMillis = now,
                project = { ProjectedPoint(0f, 0f) },
            )
        assertTrue(result.onScreen.isEmpty())
        assertTrue(result.offScreen.isEmpty())
    }

    @Test
    fun `no members plans nothing`() {
        val result = planWith(emptyList())
        assertTrue(result.onScreen.isEmpty())
        assertTrue(result.offScreen.isEmpty())
    }

    // ---- merging and capping -----------------------------------------------

    @Test
    fun `two members in the same direction sector collapse into one arrow`() {
        val members =
            listOf(
                member("near", latitude = cameraLat + 0.30),
                member("far", latitude = cameraLat + 0.60),
            )
        val arrows = planWith(members).offScreen
        assertEquals(1, arrows.size)
        // The nearest speaks for the sector, with the other on the badge.
        assertEquals("near", arrows.single().member.uid)
        assertEquals(1, arrows.single().extraCount)
    }

    @Test
    fun `members in different sectors keep separate arrows`() {
        val members =
            listOf(
                member("north", latitude = cameraLat + 0.5),
                member("south", latitude = cameraLat - 0.5),
            )
        val arrows = planWith(members).offScreen
        assertEquals(2, arrows.size)
        assertEquals(setOf("north", "south"), arrows.map { it.member.uid }.toSet())
    }

    /**
     * Twelve members, one in the MIDDLE of each of the twelve direction sectors
     * (hence the 15 degree offset — sitting on a sector boundary would make the
     * test hostage to rounding). Longitude is scaled by 1/cos(latitude) so the
     * degree offsets really do come out as the intended compass bearings this
     * far north.
     */
    private fun ringOfTwelve(): List<ConvoyMemberPosition> =
        (0 until 12).map { index ->
            val angle = Math.toRadians(15.0 + index * 30.0)
            member(
                "m$index",
                latitude = cameraLat + 0.5 * kotlin.math.cos(angle),
                longitude =
                    cameraLng + 0.5 * kotlin.math.sin(angle) / kotlin.math.cos(Math.toRadians(cameraLat)),
            )
        }

    @Test
    fun `the ring fixture really does occupy twelve distinct sectors`() {
        // Guards the two tests below: if the fixture ever collapsed into fewer
        // sectors they would pass for the wrong reason.
        val sectors =
            ringOfTwelve()
                .map {
                    ConvoyArrowPlanner.sectorOf(
                        ConvoyEdgeGeometry.initialBearingDegrees(
                            cameraLat,
                            cameraLng,
                            it.latitude,
                            it.longitude,
                        ),
                    )
                }
                .toSet()
        assertEquals(12, sectors.size)
    }

    @Test
    fun `arrows are capped rather than ringing the screen`() {
        val arrows = planWith(ringOfTwelve()).offScreen
        assertEquals(ConvoyArrowPlanner.MAX_ARROWS, arrows.size)
    }

    @Test
    fun `capped-away members are folded into the badges so the counts still add up`() {
        val arrows = planWith(ringOfTwelve()).offScreen
        val represented = arrows.sumOf { 1 + it.extraCount }
        assertEquals(12, represented)
    }

    @Test
    fun `the nearest arrow comes first`() {
        val members =
            listOf(
                member("far", latitude = cameraLat + 0.9),
                member("near", longitude = cameraLng + 0.2),
            )
        val arrows = planWith(members).offScreen
        assertEquals("near", arrows.first().member.uid)
    }

    @Test
    fun `a lone off-screen member carries no extra count`() {
        val arrows = planWith(listOf(member("solo", latitude = cameraLat + 0.5))).offScreen
        assertEquals(0, arrows.single().extraCount)
    }

    @Test
    fun `equidistant members in a sector pick a stable representative`() {
        val a = member("aaa", latitude = cameraLat + 0.5)
        val b = member("bbb", latitude = cameraLat + 0.5)
        val first = planWith(listOf(a, b)).offScreen.single().member.uid
        val second = planWith(listOf(b, a)).offScreen.single().member.uid
        assertEquals(first, second)
    }

    // ---- sector helper -----------------------------------------------------

    @Test
    fun `sectors partition the circle without gaps or overlap`() {
        assertEquals(0, ConvoyArrowPlanner.sectorOf(0.0))
        assertEquals(0, ConvoyArrowPlanner.sectorOf(29.9))
        assertEquals(1, ConvoyArrowPlanner.sectorOf(30.0))
        assertEquals(11, ConvoyArrowPlanner.sectorOf(359.9))
        assertEquals(0, ConvoyArrowPlanner.sectorOf(360.0))
        assertEquals(11, ConvoyArrowPlanner.sectorOf(-1.0))
    }
}
