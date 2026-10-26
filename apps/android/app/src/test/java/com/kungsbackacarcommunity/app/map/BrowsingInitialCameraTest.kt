package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.location.LastKnownLocation
import com.kungsbackacarcommunity.app.navigation.NavInitialCamera
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowsingInitialCameraTest {
    @Test fun savedFocusAppliesToCachedLocationBeforeFirstFix() {
        val fix = LastKnownLocation(latitude = 57.49, longitude = 12.08)
        val camera = BrowsingInitialCamera.resolve(fix, 13.5)
        assertEquals(fix.latitude, camera.latitude, 0.0)
        assertEquals(fix.longitude, camera.longitude, 0.0)
        assertEquals(13.5, camera.zoom, 0.0)
    }

    @Test fun savedFocusAlsoAppliesWhenNoLocationIsCached() {
        val camera = BrowsingInitialCamera.resolve(null, 17.5)
        assertEquals(MapMarkers.DEFAULT_CAMERA.latitude, camera.latitude, 0.0)
        assertEquals(MapMarkers.DEFAULT_CAMERA.longitude, camera.longitude, 0.0)
        assertEquals(17.5, camera.zoom, 0.0)
    }

    @Test fun focusIsSnappedAndClampedWithoutChangingNavigationFraming() {
        assertEquals(MapZoomPreference.MIN_ZOOM, BrowsingInitialCamera.resolve(null, -1.0).zoom, 0.0)
        assertEquals(MapZoomPreference.MAX_ZOOM, BrowsingInitialCamera.resolve(null, 40.0).zoom, 0.0)
        assertEquals(14.5, BrowsingInitialCamera.resolve(null, 14.4).zoom, 0.0)
        assertEquals(MapZoomPreference.DEFAULT_ZOOM, BrowsingInitialCamera.resolve(null, Double.NaN).zoom, 0.0)
        assertEquals(MapMarkers.DEFAULT_CAMERA, NavInitialCamera.resolve(null))
    }
}
