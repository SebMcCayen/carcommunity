package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the end-of-session route map: the in-memory recorded fixes are
 * converted 1:1, and a route too short to draw resolves to an empty list so the
 * summary shows its note instead of an empty map. (The GL rendering itself is
 * on-device only.)
 */
class SessionRoutePreviewTest {
    private fun point(lat: Double, lng: Double, t: Long) = RecordedPoint(lat, lng, t)

    @Test
    fun noPoints_isNotDrawable() {
        assertTrue(SessionRoutePreview.routePoints(emptyList()).isEmpty())
    }

    @Test
    fun singlePoint_isNotDrawable() {
        // One fix is a dot, not a road: a polyline needs two points.
        val route = SessionRoutePreview.routePoints(listOf(point(57.0, 12.0, 1_000L)))

        assertTrue("A one-point drive has no route to draw", route.isEmpty())
    }

    @Test
    fun twoPoints_areDrawableAndConvertedInOrder() {
        val route =
            SessionRoutePreview.routePoints(
                listOf(point(57.0, 12.0, 1_000L), point(57.1, 12.2, 61_000L)),
            )

        assertEquals(
            listOf(
                RoutePoint(57.0, 12.0, 1_000L),
                RoutePoint(57.1, 12.2, 61_000L),
            ),
            route,
        )
    }

    @Test
    fun everyFixIsKept_soTheSummaryDrawsTheSameRouteHistoryWill() {
        // A GPS jump (a whole degree apart within a second) is the kind of fix
        // DriveSummary excludes from DISTANCE. The route must NOT filter it: the
        // uploaded route.bin keeps every fix and History's replay draws every one,
        // so filtering here would show two different routes for one drive.
        val recorded =
            listOf(
                point(57.0, 12.0, 1_000L),
                point(58.0, 13.0, 2_000L),
                point(57.0001, 12.0001, 3_000L),
            )

        val route = SessionRoutePreview.routePoints(recorded)

        assertEquals(recorded.map { it.toRoutePoint() }, route)
        assertEquals(recorded.size, route.size)
    }

    @Test
    fun longRouteKeepsEveryFix() {
        val recorded = (0 until 500).map { point(57.0 + it * 1e-4, 12.0 + it * 1e-4, it * 1_000L) }

        val route = SessionRoutePreview.routePoints(recorded)

        assertEquals(500, route.size)
        assertEquals(recorded.first().toRoutePoint(), route.first())
        assertEquals(recorded.last().toRoutePoint(), route.last())
    }
}
