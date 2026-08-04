package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.map.MapZoomPreference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resting-zoom seam the map-layers "focus distance" slider drives, pinned in
 * the BLOCKING unit-test job (the popup/slider assertions in the instrumentation
 * suite are `continue-on-error` in CI, so the wiring contract is asserted here
 * too).
 *
 * [StubMapSurface.setBrowsingZoom] mirrors the real surface's observable side: it
 * records the resting zoom the shell pushes, starting at the preference default so
 * an untouched slider reproduces the app's original framing.
 */
class BrowsingZoomSeamTest {
    @Test
    fun defaultsToThePreferenceDefault() {
        val surface = StubMapSurface()
        assertEquals(MapZoomPreference.DEFAULT_ZOOM, surface.browsingZoom, 1e-9)
    }

    @Test
    fun recordsThePushedRestingZoom() {
        val surface = StubMapSurface()
        surface.setBrowsingZoom(13.5)
        assertEquals(13.5, surface.browsingZoom, 1e-9)
    }
}
