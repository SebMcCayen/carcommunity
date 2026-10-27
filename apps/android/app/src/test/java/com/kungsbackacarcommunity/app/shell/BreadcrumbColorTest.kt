package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.graphics.toArgb
import com.kungsbackacarcommunity.app.design.KccPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `oldest gold core still contrasts with its casing`() {
        // Test the worst point of the actual opacity ramp, after compositing
        // over the opaque casing. A near-transparent tail fails this even when
        // the head's brand colour is bright enough on its own.
        val oldestCore = composite(
            MapboxMapSurface().breadcrumbColorArgb,
            MapboxMapSurface.BREADCRUMB_CASING_COLOR,
            MapboxMapSurface.BREADCRUMB_TAIL_ALPHA,
        )
        assertTrue(
            "the oldest retained segment must remain clearly separated from its outline",
            contrast(oldestCore, MapboxMapSurface.BREADCRUMB_CASING_COLOR) >= 4.5,
        )
        assertTrue(
            "the newest segment should retain more emphasis than the oldest",
            MapboxMapSurface.BREADCRUMB_HEAD_ALPHA > MapboxMapSurface.BREADCRUMB_TAIL_ALPHA,
        )
    }

    @Test
    fun `casing separates the trail from light roads without covering nearby lanes`() {
        assertEquals(KccPalette.inkBlack.toArgb(), MapboxMapSurface.BREADCRUMB_CASING_COLOR)
        assertTrue(
            "the outline must separate the trail from pale roads",
            contrast(MapboxMapSurface.BREADCRUMB_CASING_COLOR, KccPalette.warmIvory.toArgb()) >= 7.0,
        )
        val borderWidth =
            (MapboxMapSurface.BREADCRUMB_CASING_LINE_WIDTH - MapboxMapSurface.BREADCRUMB_LINE_WIDTH) / 2
        assertTrue("a visible but restrained outline on each side", borderWidth in 1.5..2.5)
    }

    @Test
    fun `oldest segment remains readable against a representative night road`() {
        val nightRoad = 0xFF2B2B2B.toInt()
        val oldestCore = composite(
            MapboxMapSurface().breadcrumbColorArgb,
            MapboxMapSurface.BREADCRUMB_CASING_COLOR,
            MapboxMapSurface.BREADCRUMB_TAIL_ALPHA,
        )
        assertTrue("the entire tail must stand off a dark road", contrast(oldestCore, nightRoad) >= 3.0)
    }

    private fun composite(foreground: Int, background: Int, alpha: Double): Int {
        fun channel(shift: Int): Int =
            ((((foreground shr shift) and 0xFF) * alpha) +
                (((background shr shift) and 0xFF) * (1.0 - alpha))).toInt()
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val luminanceA = TrafficPalette.relativeLuminance(a)
        val luminanceB = TrafficPalette.relativeLuminance(b)
        return (maxOf(luminanceA, luminanceB) + 0.05) / (minOf(luminanceA, luminanceB) + 0.05)
    }
}
