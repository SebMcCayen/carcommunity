package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.navigation.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure naming + message-building rules ([LocationShare]) behind
 * the save-location popup and the share-to-friend flow.
 */
class LocationShareTest {
    private val point = LatLng(longitude = 12.0766, latitude = 57.49102)

    @Test
    fun `a blank name resolves to the coordinate string`() {
        assertEquals(LocationShare.coordinateName(point), LocationShare.resolveName("   ", point))
        assertEquals("57.49102, 12.07660", LocationShare.resolveName("", point))
    }

    @Test
    fun `a typed name is kept and trimmed`() {
        assertEquals("Mamma", LocationShare.resolveName("  Mamma  ", point))
    }

    @Test
    fun `the coordinate name is lat then lng at five decimals`() {
        assertEquals("57.49102, 12.07660", LocationShare.coordinateName(point))
    }

    @Test
    fun `the share message carries the resolved name and a tappable geo link`() {
        val text = LocationShare.messageText("Mamma", point)
        assertTrue(text.startsWith("Mamma"))
        // A geo: token the chat renderer detects and turns into a map chip.
        assertTrue(text.contains("geo:57.49102,12.07660"))
    }

    @Test
    fun `a blank-name share still produces a coordinate-named geo message`() {
        val text = LocationShare.messageText("  ", point)
        assertTrue(text.startsWith("57.49102, 12.07660"))
        assertTrue(text.contains("geo:57.49102,12.07660"))
    }

    @Test
    fun `the geo token in a share message round-trips through the parser`() {
        val text = LocationShare.messageText("Workshop", point)
        // Whatever we build, GeoLinks must find exactly one valid link at the point,
        // so the recipient's chat renders a tappable chip rather than plain text.
        val matches = GeoLinks.findAll(text)
        assertEquals(1, matches.size)
        assertEquals(57.49102, matches.single().link.latitude, 0.000001)
        assertEquals(12.0766, matches.single().link.longitude, 0.000001)
    }
}
