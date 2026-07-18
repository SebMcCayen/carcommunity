package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the promise TrafficPalette's KDoc makes: night congestion colours are
 * genuinely lighter than the day ones (so they read against the dark basemap),
 * and the day palette is untouched.
 */
class TrafficPaletteTest {
    @Test
    fun `night colours are lighter than their day counterparts`() {
        val day = TrafficPalette.DAY
        val night = TrafficPalette.NIGHT
        val pairs =
            listOf(
                "low" to (day.low to night.low),
                "moderate" to (day.moderate to night.moderate),
                "heavy" to (day.heavy to night.heavy),
                "severe" to (day.severe to night.severe),
                "unknown" to (day.unknown to night.unknown),
            )
        for ((name, colors) in pairs) {
            val (dayColor, nightColor) = colors
            val dayLum = TrafficPalette.relativeLuminance(dayColor)
            val nightLum = TrafficPalette.relativeLuminance(nightColor)
            assertTrue(
                "night '$name' must be lighter than day (day=$dayLum, night=$nightLum) " +
                    "or it cannot be picked out of a dark basemap",
                nightLum > dayLum,
            )
        }
    }

    /**
     * The two levels Seb actually reported ("the yellow and red part") are the
     * ones that were failing, so they get a real bar rather than "any
     * improvement". A mid-grey (#808080, luminance ~0.216) stands in for the
     * night basemap's brightest roads: both must clear it comfortably, which the
     * day values do NOT for severe.
     */
    @Test
    fun `night moderate and severe stand clear of a dark basemap`() {
        val basemapLum = TrafficPalette.relativeLuminance(0xFF808080.toInt())
        val night = TrafficPalette.NIGHT

        assertTrue(
            "night moderate must be well clear of the basemap",
            TrafficPalette.relativeLuminance(night.moderate) > basemapLum * 2,
        )
        assertTrue(
            "night severe must be clear of the basemap",
            TrafficPalette.relativeLuminance(night.severe) > basemapLum,
        )
        // Teeth: the DAY severe red is what was being drawn at night, and it is
        // DARKER than that basemap — i.e. the old behaviour genuinely failed this.
        assertTrue(
            "day severe is darker than the dark basemap — this is the bug",
            TrafficPalette.relativeLuminance(TrafficPalette.DAY.severe) < basemapLum,
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
