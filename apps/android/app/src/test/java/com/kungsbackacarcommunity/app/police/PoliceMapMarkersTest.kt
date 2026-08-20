package com.kungsbackacarcommunity.app.police

import com.kungsbackacarcommunity.app.incidents.IncidentPalette
import com.kungsbackacarcommunity.app.incidents.IncidentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure police-pin → map-marker mapping. Police markers render THROUGH the
 * shared incident marker layer, so this only pins the id namespacing and the
 * police look; the actual Mapbox draw is device-only.
 */
class PoliceMapMarkersTest {
    private fun pin(id: String) =
        PoliceReport(id = id, latitude = 57.5, longitude = 12.0, source = "manual", expiresAtIso = null)

    @Test
    fun `namespaces the marker id so it never collides with an incident id`() {
        val markers = PoliceMapMarkers.markers(listOf(pin("abc")))
        assertEquals(PoliceMapMarkers.POLICE_MARKER_ID_PREFIX + "abc", markers.single().id)
        assertTrue(markers.single().id.startsWith(PoliceMapMarkers.POLICE_MARKER_ID_PREFIX))
    }

    @Test
    fun `uses the police-blue disc and white glyph and never the reported-gone fade`() {
        val marker = PoliceMapMarkers.markers(listOf(pin("a"))).single()
        assertEquals(PoliceMapMarkers.DISC_COLOR_ARGB, marker.colorArgb)
        assertEquals(PoliceMapMarkers.GLYPH_COLOR_ARGB, marker.glyphColorArgb)
        assertEquals(false, marker.reportedCleared)
    }

    @Test
    fun `police disc is the same blue as the incidents Police category`() {
        // Seb chose the shared "police = blue" look. Pin the disc equal to the
        // incidents police blue so the two layers cannot silently drift apart.
        assertEquals(IncidentPalette.colorArgb(IncidentType.POLICE), PoliceMapMarkers.DISC_COLOR_ARGB)
    }

    @Test
    fun `maps position through unchanged and preserves order`() {
        val markers = PoliceMapMarkers.markers(listOf(pin("a"), pin("b")))
        assertEquals(listOf("police:a", "police:b"), markers.map { it.id })
        assertEquals(57.5, markers.first().latitude, 0.0)
        assertEquals(12.0, markers.first().longitude, 0.0)
    }

    @Test
    fun `empty in empty out`() {
        assertTrue(PoliceMapMarkers.markers(emptyList()).isEmpty())
    }
}
