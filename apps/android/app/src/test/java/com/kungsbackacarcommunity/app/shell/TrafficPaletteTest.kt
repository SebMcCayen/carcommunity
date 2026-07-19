package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the promises TrafficPalette's KDoc makes about the night ramp: heavy is
 * a DARK red and moderate a DARK yellow (not a pastel lift of the day palette),
 * every level still clears the night basemap, the levels stay far enough apart
 * to survive red-green colour blindness, and the day palette is untouched.
 */
class TrafficPaletteTest {
    /**
     * A realistic stand-in for the Mapbox Standard style's night basemap, which
     * is near-black rather than mid-grey. Every night colour is measured against
     * this: "dark red" is only acceptable while it still stands off the map.
     */
    private val nightBasemapLum = TrafficPalette.relativeLuminance(0xFF2B2B2B.toInt())

    /**
     * The brief: heavy reads as a DARK red and moderate as a DARK yellow. Both
     * are darker than the day pigment they replace — that is what makes them
     * "dark" rather than the previous "lifted" pastel ramp, which is the change
     * this test exists to stop being silently reverted.
     */
    @Test
    fun `night heavy is a dark red and night moderate a dark yellow`() {
        val day = TrafficPalette.DAY
        val night = TrafficPalette.NIGHT

        // Dark: darker than the day colour for the same level.
        assertTrue(
            "night heavy must be DARKER than the day heavy — it is a dark red",
            TrafficPalette.relativeLuminance(night.heavy) <
                TrafficPalette.relativeLuminance(day.heavy),
        )
        assertTrue(
            "night moderate must be DARKER than the day moderate — it is a dark yellow",
            TrafficPalette.relativeLuminance(night.moderate) <
                TrafficPalette.relativeLuminance(day.moderate),
        )

        // Red: heavy's red channel dominates both others by a clear margin.
        val hr = (night.heavy shr 16) and 0xFF
        val hg = (night.heavy shr 8) and 0xFF
        val hb = night.heavy and 0xFF
        assertTrue("night heavy is red-dominant", hr > hg * 2 && hr > hb * 2)

        // Yellow: red and green both high and close, blue clearly suppressed.
        val mr = (night.moderate shr 16) and 0xFF
        val mg = (night.moderate shr 8) and 0xFF
        val mb = night.moderate and 0xFF
        assertTrue("night moderate is yellow (red+green high)", mr > 0x80 && mg > 0x80)
        assertTrue("night moderate is yellow (blue suppressed)", mb < mg / 2)
    }

    /**
     * "Dark" must never collapse into "invisible" — that was the original
     * complaint. Every night level has to clear the near-black basemap by a real
     * margin, and the day palette's heavy/severe reds demonstrably do not, which
     * is why the night ramp exists at all.
     */
    @Test
    fun `every night colour stands clear of the near-black basemap`() {
        val night = TrafficPalette.NIGHT
        val levels =
            listOf(
                "low" to night.low,
                "moderate" to night.moderate,
                "heavy" to night.heavy,
                "severe" to night.severe,
                "unknown" to night.unknown,
            )
        for ((name, color) in levels) {
            val lum = TrafficPalette.relativeLuminance(color)
            assertTrue(
                "night '$name' (luminance $lum) must stand clear of the night " +
                    "basemap ($nightBasemapLum) — dark must not mean invisible",
                lum > nightBasemapLum * 3,
            )
        }
        // The worst level is the most salient thing on the map.
        assertTrue(
            "severe must be brighter than heavy so the worst level pops most",
            TrafficPalette.relativeLuminance(night.severe) >
                TrafficPalette.relativeLuminance(night.heavy),
        )
    }

    /**
     * Luminance is the one cue a red-green colour-blind viewer keeps, so the
     * distinction that actually drives a routing decision — moderate vs heavy —
     * must be carried by brightness, not hue alone. Green `low` additionally
     * carries a lifted blue channel so it does not collapse into the reds.
     */
    @Test
    fun `night levels stay separable without colour vision`() {
        val night = TrafficPalette.NIGHT
        val moderate = TrafficPalette.relativeLuminance(night.moderate)
        val heavy = TrafficPalette.relativeLuminance(night.heavy)
        val low = TrafficPalette.relativeLuminance(night.low)

        assertTrue(
            "moderate must be at least 2x heavy's luminance (was ${moderate / heavy}x)",
            moderate > heavy * 2,
        )
        assertTrue(
            "low must be at least 2x heavy's luminance (was ${low / heavy}x)",
            low > heavy * 2,
        )
        // Green vs red is the classic confusion pair: low's blue channel is
        // lifted well above the reds' so a deuteranope still has a hue cue.
        val lowBlue = night.low and 0xFF
        assertTrue(
            "night low needs a lifted blue channel to separate it from the reds",
            lowBlue > (night.heavy and 0xFF) * 2 && lowBlue > (night.severe and 0xFF),
        )
    }

    /** Day is deliberately unchanged — night mode must not cost the day map. */
    @Test
    fun `day palette is unchanged`() {
        assertEquals(0xFF4CAF50.toInt(), TrafficPalette.DAY.low)
        assertEquals(0xFFFFC107.toInt(), TrafficPalette.DAY.moderate)
        assertEquals(0xFFFF6F00.toInt(), TrafficPalette.DAY.heavy)
        assertEquals(0xFFD32F2F.toInt(), TrafficPalette.DAY.severe)
        assertEquals(0xFF9E9E9E.toInt(), TrafficPalette.DAY.unknown)
        assertEquals(2.5, TrafficPalette.DAY_LINE_WIDTH, 0.0)
    }

    @Test
    fun `mode selects the palette and the width`() {
        assertEquals(TrafficPalette.DAY, TrafficPalette.colors(MapMode.Day))
        assertEquals(TrafficPalette.NIGHT, TrafficPalette.colors(MapMode.Night))
        assertEquals(TrafficPalette.DAY_LINE_WIDTH, TrafficPalette.lineWidth(MapMode.Day), 0.0)
        assertEquals(TrafficPalette.NIGHT_LINE_WIDTH, TrafficPalette.lineWidth(MapMode.Night), 0.0)
        assertTrue(
            "night lines are wider — a hairline is harder to see on a dark map",
            TrafficPalette.NIGHT_LINE_WIDTH > TrafficPalette.DAY_LINE_WIDTH,
        )
    }

    @Test
    fun `every colour stays fully opaque and hue-recognisable`() {
        for (colors in listOf(TrafficPalette.DAY, TrafficPalette.NIGHT)) {
            val all =
                listOf(colors.low, colors.moderate, colors.heavy, colors.severe, colors.unknown)
            all.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
            // The layer must still read green -> yellow -> orange -> red, so the
            // levels stay distinct rather than collapsing into one bright smear.
            assertEquals(all.size, all.toSet().size)
        }
        // Hue sanity: severe is red-dominant, low is green-dominant, in BOTH
        // palettes — the night lift must not have shifted what a colour means.
        for (colors in listOf(TrafficPalette.DAY, TrafficPalette.NIGHT)) {
            val severeRed = (colors.severe shr 16) and 0xFF
            val severeGreen = (colors.severe shr 8) and 0xFF
            assertTrue("severe stays red-dominant", severeRed > severeGreen)
            val lowRed = (colors.low shr 16) and 0xFF
            val lowGreen = (colors.low shr 8) and 0xFF
            assertTrue("low stays green-dominant", lowGreen > lowRed)
        }
    }

    /** The luminance helper itself, against known anchors. */
    @Test
    fun `relative luminance matches the sRGB reference points`() {
        assertEquals(0.0, TrafficPalette.relativeLuminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, TrafficPalette.relativeLuminance(0xFFFFFFFF.toInt()), 1e-9)
        // Green dominates the weighting, blue barely registers — which is the
        // whole reason raw RGB comparison can't answer "is this brighter?".
        assertTrue(
            TrafficPalette.relativeLuminance(0xFF00FF00.toInt()) >
                TrafficPalette.relativeLuminance(0xFF0000FF.toInt()),
        )
        // Alpha is ignored.
        assertEquals(
            TrafficPalette.relativeLuminance(0xFFFF0000.toInt()),
            TrafficPalette.relativeLuminance(0x00FF0000),
            1e-9,
        )
    }
}
