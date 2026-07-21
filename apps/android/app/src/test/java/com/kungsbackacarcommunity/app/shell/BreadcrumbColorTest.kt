package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.graphics.toArgb
import com.kungsbackacarcommunity.app.design.KccPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the private breadcrumb tail's colour to the brand yellow design token.
 *
 * The tail used to be drawn in the live-share GREEN, which — reviewed only in
 * daylight — melted into the green "low congestion" band of the traffic overlay.
 * It now derives from the brand primary token (crownGold, 0xFFEAB54B). This test
 * exists to keep that promise honest: it asserts the tail colour IS the token
 * (not a hand-copied literal that could silently drift), that the R/G/B channels
 * match the token's actual value, and that it is no longer the sharing puck's
 * green — the puck pulse is a separate colour that this change must not touch.
 *
 * Pure JVM: [androidx.compose.ui.graphics.Color.toArgb] is plain colour maths, so
 * no GL surface, device or Robolectric is needed to read the derived ARGB back
 * off [MapboxMapSurface.breadcrumbColorArgb].
 */
class BreadcrumbColorTest {

    @Test
    fun `breadcrumb tail colour is the brand yellow token`() {
        val surface = MapboxMapSurface()
        assertEquals(
            "breadcrumb tail must derive from the crownGold brand token",
            KccPalette.crownGold.toArgb(),
            surface.breadcrumbColorArgb,
        )
    }

    @Test
    fun `breadcrumb RGB channels equal the token's #EAB54B value`() {
        val argb = MapboxMapSurface().breadcrumbColorArgb
        assertEquals("red channel", 0xEA, (argb shr 16) and 0xFF)
        assertEquals("green channel", 0xB5, (argb shr 8) and 0xFF)
        assertEquals("blue channel", 0x4B, argb and 0xFF)
    }

    @Test
    fun `breadcrumb tail is no longer the live-share green`() {
        // The sharing puck's pulse stays green; only the tail moved to yellow.
        assertNotEquals(
            "the breadcrumb must not collide with the traffic/pulse green again",
            KccPalette.successGreen.toArgb(),
            MapboxMapSurface().breadcrumbColorArgb,
        )
    }
}
