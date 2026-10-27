package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.navigation.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRouteGeometryTest {
    private val thumbnail = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test
    fun `missing full route yields only simplified coordinates and no timestamp statistics`() {
        val replay = RouteReplayState.Unavailable(RouteReplayFailure.MISSING)
        val map = driveRouteGeometry(replay, thumbnail, hasOwner = true)
        assertTrue(map is DriveRouteGeometry.Simplified)
        assertEquals(listOf(LatLng(latitude = 38.5, longitude = -120.2), LatLng(latitude = 40.7, longitude = -120.95), LatLng(latitude = 43.252, longitude = -126.453)), map!!.coordinates)
        assertEquals(RouteReplayFailure.MISSING, replay.reason)
        assertNull(routeReplayTopSpeed(replay))
    }

    @Test
    fun `real fixes win over simplified geometry and retain their real timestamps and speed`() {
        val points = listOf(RoutePoint(57.0, 12.0, 1_000L), RoutePoint(57.0001, 12.0, 11_000L))
        val replay = RouteReplayState.Ready(points)
        val map = driveRouteGeometry(replay, thumbnail, hasOwner = true) as DriveRouteGeometry.Recorded
        assertSame(points, map.points)
        assertEquals(points.map { LatLng(latitude = it.latitude, longitude = it.longitude) }, map.coordinates)
        assertEquals(listOf(1_000L, 11_000L), map.points.map { it.timestampMs })
        assertEquals(DriveSummary.topSpeedMetersPerSecond(points), routeReplayTopSpeed(replay))
        assertTrue(routeReplayTopSpeed(replay)!! > 0.0)
    }

    @Test
    fun `auth access and not-found denials cannot use a saved geometry fallback`() {
        for (failure in listOf(RouteReplayFailure.SIGN_IN_REQUIRED, RouteReplayFailure.ACCESS_DENIED, RouteReplayFailure.NOT_FOUND)) {
            assertNull(driveRouteGeometry(RouteReplayState.Unavailable(failure), thumbnail, hasOwner = true))
        }
        assertNull(driveRouteGeometry(RouteReplayState.Unavailable(RouteReplayFailure.MISSING), thumbnail, hasOwner = false))
        assertNull(driveRouteGeometry(RouteReplayState.Loading, thumbnail, hasOwner = true))
    }

    @Test
    fun `missing corrupt off-globe and stationary geometry never become a map`() {
        val replay = RouteReplayState.Unavailable(RouteReplayFailure.MISSING)
        for (encoded in listOf(null, "_", "not a polyline!!", "_p~iF~ps|U??")) {
            assertNull(driveRouteGeometry(replay, encoded, hasOwner = true))
        }
    }

    @Test
    fun `empty exact route can still show stored simplified geometry without claiming full replay`() {
        val replay = RouteReplayState.Ready(emptyList())
        assertTrue(driveRouteGeometry(replay, thumbnail, hasOwner = true) is DriveRouteGeometry.Simplified)
        assertTrue(replay.points.isEmpty())
        assertNull(routeReplayTopSpeed(replay))
    }
}
