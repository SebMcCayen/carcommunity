package com.kungsbackacarcommunity.app.navigation.turnbyturn

import com.kungsbackacarcommunity.app.shell.TrafficPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The turn-by-turn route line has one job: make the road you are supposed to
 * drive unmistakable. That fails in two ways, and both are checkable off-device:
 * the route being confusable with something else already drawn on the map, and
 * the travelled part not being visibly de-emphasised.
 *
 * Colours are asserted as RELATIONSHIPS, never as literals: pinning the exact
 * ARGB would just restate [NavRouteLinePalette] and would fail on any tasteful
 * tweak while catching nothing.
 */
class NavRouteLineColorsTest {
    private fun r(argb: Int) = (argb shr 16) and 0xFF

    private fun g(argb: Int) = (argb shr 8) and 0xFF

    private fun b(argb: Int) = argb and 0xFF

    /** Rec. 709 relative luminance, 0..255. */
    private fun luminance(argb: Int): Double =
        0.2126 * r(argb) + 0.7152 * g(argb) + 0.0722 * b(argb)

    /** Crude channel distance — enough to prove two colours are not neighbours. */
    private fun distance(a: Int, b2: Int): Int =
        abs(r(a) - r(b2)) + abs(g(a) - g(b2)) + abs(b(a) - b(b2))

    private val palettes = listOf(NavRouteLinePalette.DAY, NavRouteLinePalette.NIGHT)

    @Test
    fun `forNight picks the night palette and forDay the day one`() {
        assertEquals(NavRouteLinePalette.NIGHT, NavRouteLinePalette.forNight(true))
        assertEquals(NavRouteLinePalette.DAY, NavRouteLinePalette.forNight(false))
    }

    @Test
    fun `every colour is fully opaque`() {
        palettes.forEach { p ->
            listOf(
                p.remaining,
                p.remainingCasing,
                p.traveled,
                p.traveledCasing,
                p.closure,
                p.restricted,
            ).forEach { argb ->
                assertEquals(0xFF, (argb ushr 24) and 0xFF)
            }
        }
    }

    /**
     * The remaining route is BLUE-dominant. This is the property that keeps it
     * out of the traffic ramp's hue family (green → amber → orange → red), out of
     * the brand-gold breadcrumb tail, and out of the red destination marker.
     */
    @Test
    fun `remaining route is blue-dominant on both basemaps`() {
        palettes.forEach { p ->
            assertTrue(
                "remaining must be blue-dominant, was ${Integer.toHexString(p.remaining)}",
                b(p.remaining) > r(p.remaining) + 40 && b(p.remaining) > g(p.remaining) + 20,
            )
        }
    }

    /**
     * The whole reported bug was "I can't tell which road I'm supposed to drive".
     * The remaining route therefore has to be clearly separated from the part
     * already driven — not merely a different value of the same colour.
     */
    @Test
    fun `travelled portion is de-emphasised, not just re-tinted`() {
        palettes.forEach { p ->
            assertTrue(
                "travelled must be far from remaining",
                distance(p.traveled, p.remaining) > 90,
            )
            // Less saturated: the blue channel no longer runs away from the others.
            val remainingSpread = b(p.remaining) - minOf(r(p.remaining), g(p.remaining))
            val traveledSpread = b(p.traveled) - minOf(r(p.traveled), g(p.traveled))
            assertTrue(
                "travelled must be less saturated than remaining",
                traveledSpread < remainingSpread,
            )
        }
    }

    /**
     * The casing exists to hold the line's edge against the basemap, so it has to
     * actually contrast with the fill it outlines.
     */
    @Test
    fun `casings contrast with the lines they outline`() {
        palettes.forEach { p ->
            assertTrue(
                "route casing must be darker than the route",
                luminance(p.remainingCasing) < luminance(p.remaining) - 20,
            )
            assertTrue(
                "travelled casing must differ from the travelled line",
                distance(p.traveledCasing, p.traveled) > 15,
            )
        }
    }

    /**
     * Day and night are genuinely different treatments, not the same value twice:
     * the night route must be LIGHTER (it sits on a dark basemap) and its casing
     * darker than the day one.
     */
    @Test
    fun `night route is lighter than the day route and its casing darker`() {
        assertTrue(
            luminance(NavRouteLinePalette.NIGHT.remaining) >
                luminance(NavRouteLinePalette.DAY.remaining),
        )
        assertTrue(
            luminance(NavRouteLinePalette.NIGHT.remainingCasing) <
                luminance(NavRouteLinePalette.DAY.remainingCasing),
        )
    }

    /**
     * The route must not be mistakable for the traffic congestion overlay, which
     * can be switched on over the SAME roads from the layers popup. Checked
     * against every band of both ramps.
     */
    @Test
    fun `route never collides with a traffic congestion band`() {
        val bands =
            listOf(TrafficPalette.DAY, TrafficPalette.NIGHT).flatMap {
                listOf(it.low, it.moderate, it.heavy, it.severe, it.unknown)
            }
        palettes.forEach { p ->
            bands.forEach { band ->
                assertTrue(
                    "route ${Integer.toHexString(p.remaining)} too close to congestion " +
                        Integer.toHexString(band),
                    distance(p.remaining, band) > 120,
                )
            }
        }
    }

    /**
     * A closure is a road you cannot use; it must not read as the route. (It is a
     * property of the ROAD, not a reading of the driver's speed — nothing in this
     * palette is derived from speed.)
     */
    @Test
    fun `closures and restricted sections are distinct from the route`() {
        palettes.forEach { p ->
            assertNotEquals(p.remaining, p.closure)
            assertNotEquals(p.remaining, p.restricted)
            assertTrue(distance(p.remaining, p.closure) > 120)
            // Closures are red-dominant, the traffic idiom for "do not go here".
            assertTrue(r(p.closure) > b(p.closure) + 60)
        }
    }
}
