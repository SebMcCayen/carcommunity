package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The render step Seb's bug was missing: an active admin `crownHuntPoints` point
 * turns into a crown marker at its location — and the visibility gate that Part 2
 * adds hides the whole layer when the member opts out.
 *
 * Pure ([CrownPointMarkers] carries the drawable id through without touching
 * Android), so "a created/eligible point yields a marker at its coordinate" and
 * "hidden when not participating" are proven here rather than inferred from an
 * empty map.
 */
class CrownPointMarkersTest {
    private val glyphRes = 4242

    private fun point(
        id: String = "p1",
        latitude: Double? = 57.5,
        longitude: Double? = 12.07,
    ) = CrownHuntPoint(
        id = id,
        title = "Torg",
        description = null,
        rewardPoints = 50,
        latitude = latitude,
        longitude = longitude,
        geofenceRadiusMeters = 40.0,
    )

    // ---- The gating decision ---------------------------------------------

    @Test
    fun crownsVisibleNeedsBothFeatureFlagAndParticipation() {
        assertTrue(CrownPointMarkers.crownsVisible(featureEnabled = true, participating = true))
        assertFalse(
            "opted out hides the game even with the feature on",
            CrownPointMarkers.crownsVisible(featureEnabled = true, participating = false),
        )
        assertFalse(
            "feature off hides the game even while participating",
            CrownPointMarkers.crownsVisible(featureEnabled = false, participating = true),
        )
        assertFalse(CrownPointMarkers.crownsVisible(featureEnabled = false, participating = false))
    }

    // ---- Eligible point → a marker at its location -----------------------

    @Test
    fun anEligiblePointYieldsAMarkerAtItsCoordinate() {
        val markers = CrownPointMarkers.markers(listOf(point()), visible = true, glyphRes = glyphRes)
        assertEquals(1, markers.size)
        val marker = markers.single()
        assertEquals("p1", marker.id)
        assertEquals(57.5, marker.latitude, 0.0)
        assertEquals(12.07, marker.longitude, 0.0)
        assertEquals(CrownMarkerStyle.ADMIN_POINT_DISC, marker.discColorArgb)
        assertEquals(glyphRes, marker.iconRes)
        assertEquals(CrownMarkerStyle.adminPointGlyphColorArgb(), marker.glyphColorArgb)
        // Admin points carry no glow — that stays the legendary spawn tier's.
        assertNull(marker.glowColorArgb)
    }

    // ---- Not participating → nothing drawn -------------------------------

    @Test
    fun notVisibleDrawsNothingEvenWithPoints() {
        val markers =
            CrownPointMarkers.markers(listOf(point(), point(id = "p2")), visible = false, glyphRes = glyphRes)
        assertTrue("opted out / feature off must draw no crowns", markers.isEmpty())
    }

    // ---- A point with no coordinate is skipped, not drawn at (0,0) --------

    @Test
    fun aPointMissingACoordinateIsSkipped() {
        val markers =
            CrownPointMarkers.markers(
                listOf(point(id = "noLat", latitude = null), point(id = "noLon", longitude = null), point(id = "ok")),
                visible = true,
                glyphRes = glyphRes,
            )
        assertEquals(listOf("ok"), markers.map { it.id })
    }
}
