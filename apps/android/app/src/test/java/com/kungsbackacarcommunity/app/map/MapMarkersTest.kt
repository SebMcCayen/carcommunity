package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.live.LiveMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMarkersTest {

    @Test
    fun `default camera is Kungsbacka at neighbourhood zoom`() {
        val c = MapMarkers.DEFAULT_CAMERA
        assertEquals(12.0757, c.longitude, 1e-6)
        assertEquals(57.4874, c.latitude, 1e-6)
        assertEquals(15.0, c.zoom, 1e-6)
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

    // --- Live-marker list building ---

    private fun live(uid: String, lng: Double, lat: Double, name: String? = null) =
        LiveMarker(uid = uid, latitude = lat, longitude = lng, displayName = name)

    @Test
    fun `markers puts own first as OWN kind and others as OTHER`() {
        val list =
            MapMarkers.markers(
                own = live("me", 12.0, 57.0, "Me"),
                others = listOf(live("a", 13.0, 58.0, "A"), live("b", 14.0, 59.0)),
            )
        assertEquals(3, list.size)
        assertEquals(MapMarkerKind.OWN, list[0].kind)
        assertEquals("me", list[0].uid)
        assertTrue(list.drop(1).all { it.kind == MapMarkerKind.OTHER })
        assertEquals(listOf("a", "b"), list.drop(1).map { it.uid })
    }

    @Test
    fun `markers drops null and coordinate-less others`() {
        val list =
            MapMarkers.markers(
                own = null,
                others = listOf(null, live("a", 13.0, 58.0), null),
            )
        assertEquals(1, list.size)
        assertEquals("a", list[0].uid)
        assertEquals(MapMarkerKind.OTHER, list[0].kind)
    }

    @Test
    fun `markers is empty when nobody is sharing`() {
        assertTrue(MapMarkers.markers(own = null, others = listOf(null, null)).isEmpty())
    }

    @Test
    fun `markers never duplicates the own uid among others`() {
        val list =
            MapMarkers.markers(
                own = live("me", 12.0, 57.0),
                others = listOf(live("me", 12.1, 57.1), live("a", 13.0, 58.0)),
            )
        assertEquals(listOf("me", "a"), list.map { it.uid })
        assertEquals(MapMarkerKind.OWN, list[0].kind)
        // The own coordinate wins, not the stray other-copy of the same uid.
        assertEquals(12.0, list[0].longitude, 1e-6)
    }

    @Test
    fun `markers de-duplicates repeated other uids`() {
        val list =
            MapMarkers.markers(
                own = null,
                others = listOf(live("a", 13.0, 58.0), live("a", 13.5, 58.5)),
            )
        assertEquals(1, list.size)
        assertEquals("a", list[0].uid)
        assertEquals(13.0, list[0].longitude, 1e-6)
    }

    // --- Camera over a marker list ---

    @Test
    fun `cameraForMarkers falls back to default when empty`() {
        assertEquals(MapMarkers.DEFAULT_CAMERA, MapMarkers.cameraForMarkers(emptyList()))
    }

    @Test
    fun `cameraForMarkers focuses on the own marker when present`() {
        val markers =
            MapMarkers.markers(
                own = live("me", 12.5, 57.6),
                others = listOf(live("a", 20.0, 60.0)),
            )
        val camera = MapMarkers.cameraForMarkers(markers)
        assertEquals(12.5, camera.longitude, 1e-6)
        assertEquals(57.6, camera.latitude, 1e-6)
        assertEquals(MapMarkers.OWN_MARKER_ZOOM, camera.zoom, 1e-6)
    }

    @Test
    fun `cameraForMarkers focuses on first other when no own marker`() {
        val markers =
            MapMarkers.markers(
                own = null,
                others = listOf(live("a", 15.0, 59.0), live("b", 16.0, 60.0)),
            )
        val camera = MapMarkers.cameraForMarkers(markers)
        assertEquals(15.0, camera.longitude, 1e-6)
        assertEquals(59.0, camera.latitude, 1e-6)
        assertEquals(MapMarkers.OWN_MARKER_ZOOM, camera.zoom, 1e-6)
    }
}
