package com.kungsbackacarcommunity.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapMarkersTest {

    @Test
    fun `default camera is Kungsbacka at town zoom`() {
        val c = MapMarkers.DEFAULT_CAMERA
        assertEquals(12.0757, c.longitude, 1e-6)
        assertEquals(57.4874, c.latitude, 1e-6)
        assertEquals(11.0, c.zoom, 1e-6)
    }

    @Test
    fun `ownMarker is null when either coordinate is missing`() {
        assertNull(MapMarkers.ownMarker(longitude = null, latitude = null))
        assertNull(MapMarkers.ownMarker(longitude = 12.0, latitude = null))
        assertNull(MapMarkers.ownMarker(longitude = null, latitude = 57.0))
    }

    @Test
    fun `ownMarker carries both coordinates when present`() {
        val marker = MapMarkers.ownMarker(longitude = 12.5, latitude = 57.6)
        assertEquals(MapMarker(longitude = 12.5, latitude = 57.6), marker)
    }

    @Test
    fun `cameraFor falls back to the default when no marker`() {
        assertEquals(MapMarkers.DEFAULT_CAMERA, MapMarkers.cameraFor(null))
    }

    @Test
    fun `cameraFor focuses on the marker at the own-marker zoom`() {
        val marker = MapMarker(longitude = 12.5, latitude = 57.6)
        val camera = MapMarkers.cameraFor(marker)
        assertEquals(12.5, camera.longitude, 1e-6)
        assertEquals(57.6, camera.latitude, 1e-6)
        assertEquals(MapMarkers.OWN_MARKER_ZOOM, camera.zoom, 1e-6)
    }
}
