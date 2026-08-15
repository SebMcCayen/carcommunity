package com.kungsbackacarcommunity.app.navigation

import com.kungsbackacarcommunity.app.location.LastKnownLocation
import com.kungsbackacarcommunity.app.map.MapMarkers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure "where does the camera open" decision: a cached fix opens the map on
 * the user at street level; no cache falls back to the Kungsbacka default. This
 * is what stops navigation (and the map home) from flying in from the world
 * camera while waiting for the first GPS fix.
 */
class NavInitialCameraTest {

    @Test
    fun `cached fix opens on the user at street zoom`() {
        val cached = LastKnownLocation(latitude = 59.3293, longitude = 18.0686) // Stockholm

        val camera = NavInitialCamera.resolve(cached)

        assertEquals(cached.latitude, camera.latitude, 0.0)
        assertEquals(cached.longitude, camera.longitude, 0.0)
        assertEquals(NavInitialCamera.CACHED_ZOOM, camera.zoom, 0.0)
    }

    @Test
    fun `no cache falls back to the Kungsbacka default`() {
        val camera = NavInitialCamera.resolve(null)

        assertEquals(MapMarkers.DEFAULT_CAMERA, camera)
        // Sanity-check the fallback is actually Kungsbacka, not mid-Sweden or 0,0.
        assertEquals(57.4874, camera.latitude, 0.0001)
        assertEquals(12.0757, camera.longitude, 0.0001)
    }

    @Test
    fun `cached zoom is street level, closer than the regional fallback`() {
        // A cached fix should open TIGHTER than the town-wide fallback, so the
        // user lands on their street rather than surveying the region.
        assert(NavInitialCamera.CACHED_ZOOM > MapMarkers.DEFAULT_CAMERA.zoom) {
            "cached zoom ${NavInitialCamera.CACHED_ZOOM} should exceed " +
                "fallback ${MapMarkers.DEFAULT_CAMERA.zoom}"
        }
    }
}
