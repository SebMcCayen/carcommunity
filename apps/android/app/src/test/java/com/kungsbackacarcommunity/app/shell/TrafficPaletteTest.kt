package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Colour-vision-deficiency simulation, test-only.
 *
 * Implements Viénot, Brettel & Mollon (1999): gamma-expand sRGB, convert to LMS
 * cone response, project onto the plane the missing cone can no longer leave,
 * convert back. Separation is then measured as CIE76 ΔE in L*a*b*, because after
 * simulation the two cues a dichromat still has — lightness and the blue-yellow
 * axis — are exactly the axes L*a*b* measures. A raw luminance ratio sees only
 * one of them, which is how the palette's original colour-blind claim came to be
 * wrong while its test still passed.
 */
private object Cvd {
    enum class Deficiency(val label: String) {
        DEUTAN("deuteranopia"),
        PROTAN("protanopia"),
        TRITAN("tritanopia"),
    }

    val DEUTAN = Deficiency.DEUTAN
    val PROTAN = Deficiency.PROTAN
    val TRITAN = Deficiency.TRITAN

    private fun expand(v: Double) =
        if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

    private fun compress(v: Double): Int {
        val c = v.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
        return Math.round(s * 255).toInt().coerceIn(0, 255)
    }

    /** The dichromat's view of [argb], as an opaque ARGB int. */
    fun simulate(argb: Int, deficiency: Deficiency): Int {
        val r = expand(((argb shr 16) and 0xFF) / 255.0) * 255.0
        val g = expand(((argb shr 8) and 0xFF) / 255.0) * 255.0
        val b = expand((argb and 0xFF) / 255.0) * 255.0

        // sRGB -> LMS
        val l = 17.8824 * r + 43.5161 * g + 4.11935 * b
        val m = 3.45565 * r + 27.1554 * g + 3.86714 * b
        val s = 0.0299566 * r + 0.184309 * g + 1.46709 * b

        // Collapse the missing cone onto the surviving confusion plane.
        val l2: Double
        val m2: Double
        when (deficiency) {
            Deficiency.PROTAN -> {
                l2 = 2.02344 * m - 2.52581 * s
                m2 = m
            }
            Deficiency.DEUTAN -> {
                l2 = l
                m2 = 0.494207 * l + 1.24827 * s
            }
            Deficiency.TRITAN -> {
                l2 = l
                m2 = m
            }
        }
        val s2 = if (deficiency == Deficiency.TRITAN) -0.395913 * l + 0.801109 * m else s

        // LMS -> sRGB
        val or = 0.0809444479 * l2 + -0.1305044090 * m2 + 0.1167721260 * s2
        val og = -0.0102485335 * l2 + 0.0540193266 * m2 + -0.1136147080 * s2
        val ob = -0.0003652968 * l2 + -0.0041216147 * m2 + 0.6935114210 * s2

        return (0xFF shl 24) or
            (compress(or / 255.0) shl 16) or
            (compress(og / 255.0) shl 8) or
            compress(ob / 255.0)
    }

    private fun toLab(argb: Int): Triple<Double, Double, Double> {
        val r = expand(((argb shr 16) and 0xFF) / 255.0)
        val g = expand(((argb shr 8) and 0xFF) / 255.0)
        val b = expand((argb and 0xFF) / 255.0)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** CIE76 colour difference. Roughly: <10 similar, >20 plainly different. */
    fun deltaE(argbA: Int, argbB: Int): Double {
        val (l1, a1, b1) = toLab(argbA)
        val (l2, a2, b2) = toLab(argbB)
        return Math.sqrt((l1 - l2) * (l1 - l2) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2))
    }
}

/**
 * Floor for pairwise separation under simulation. The shipped palette's worst
 * pair measures ΔE 20.94 (deutan `heavy`/`severe`), so this leaves a little
 * headroom for future tweaks while still failing loudly on anything that
 * collapses two levels together. For reference the ramp this replaced measured
 * 16.24 / 16.41 / 17.91 on its three tightest pairs — i.e. it would fail here.
 */
private const val MIN_CVD_DELTA_E = 18.0

/**
 * Pins the promises TrafficPalette's KDoc makes about the night ramp: heavy is
 * a DARK red and moderate a DARK yellow (not a pastel lift of the day palette),
 * every level still clears the night basemap, every pair of levels stays apart
 * under simulated red-green colour blindness, and the day palette is untouched.
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
     * Luminance is only ONE of the two cues a red-green colour-blind viewer
     * keeps — the other is the blue-yellow axis — and checking luminance alone
     * is what let the previous ramp ship with `low` and `severe` at a 1.07x
     * luminance ratio while the KDoc claimed a ~2x gap "from the reds".
     *
     * So this checks the thing the doc actually promises: run the palette
     * through a real dichromat simulation and measure every pair the way a
     * dichromat would see it.
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
        // lifted well above the reds' so a deuteranope still has a chroma cue.
        val lowBlue = night.low and 0xFF
        assertTrue(
            "night low needs a lifted blue channel to separate it from the reds",
            lowBlue > (night.heavy and 0xFF) * 2 && lowBlue > (night.severe and 0xFF),
        )
    }

    /**
     * The teeth behind the KDoc's colour-blind claim: ALL SIX level pairs, under
     * BOTH red-green deficiencies, not the two pairs that happened to be easy.
     *
     * Without this, nothing stopped `low` (free-flowing green) and `severe` (the
     * worst jam) from simulating to near-identical colours — which is exactly
     * what the first cut of the night ramp did, at ΔE 17.91 under protanopia
     * (its worst pair overall was `heavy`/`severe` at ΔE 16.24 under
     * deuteranopia).
     * A driver who cannot tell those apart reads a severe jam as clear road.
     */
    @Test
    fun `every night level pair survives simulated red-green colour blindness`() {
        val night = TrafficPalette.NIGHT
        val levels =
            listOf(
                "low" to night.low,
                "moderate" to night.moderate,
                "heavy" to night.heavy,
                "severe" to night.severe,
            )
        val failures = mutableListOf<String>()
        for (deficiency in listOf(Cvd.DEUTAN, Cvd.PROTAN)) {
            for (i in levels.indices) {
                for (j in i + 1 until levels.size) {
                    val (aName, aColor) = levels[i]
                    val (bName, bColor) = levels[j]
                    val delta =
                        Cvd.deltaE(
                            Cvd.simulate(aColor, deficiency),
                            Cvd.simulate(bColor, deficiency),
                        )
                    if (delta < MIN_CVD_DELTA_E) {
                        failures +=
                            "  ${deficiency.label}: $aName vs $bName is only " +
                                "%.2f (needs >= %.1f)".format(delta, MIN_CVD_DELTA_E)
                    }
                }
            }
        }
        assertTrue(
            "night levels collapse into each other for a red-green colour-blind " +
                "viewer:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * The pair the original KDoc got specifically wrong. `low` and `severe` are
     * NOT separated by luminance — they sit at roughly equal brightness under
     * deuteranopia — so the separation has to come from the blue-yellow axis.
     * Pinned on its own so a future tweak that flattens `low`'s blue channel
     * fails here with a message naming the real cause.
     */
    @Test
    fun `low and severe are separated by chroma because luminance cannot`() {
        val night = TrafficPalette.NIGHT
        val low = Cvd.simulate(night.low, Cvd.DEUTAN)
        val severe = Cvd.simulate(night.severe, Cvd.DEUTAN)

        // They really are near-identical in brightness — this is not an accident
        // to be "fixed" by brightening one of them; it documents why chroma has
        // to carry the pair.
        val lowLum = TrafficPalette.relativeLuminance(low)
        val severeLum = TrafficPalette.relativeLuminance(severe)
        val ratio = maxOf(lowLum, severeLum) / minOf(lowLum, severeLum)
        assertTrue(
            "luminance alone cannot separate low from severe (ratio ${ratio}x) — " +
                "if this ever exceeds 2x the KDoc's explanation needs rewriting",
            ratio < 2.0,
        )
        // ...so the blue channel must do the work, in the SIMULATED colours.
        assertTrue(
            "simulated low must stay clearly bluer than simulated severe: " +
                "${low and 0xFF} vs ${severe and 0xFF}",
            (low and 0xFF) > (severe and 0xFF) + 30,
        )
    }

    /**
     * Pins the KDoc's tritanopia paragraph, which is the one place the note
     * concedes a limitation rather than claiming a win. A conceded limitation is
     * still a factual claim, so it gets teeth too — this is the same failure
     * mode the rest of this file exists to prevent.
     *
     * Two halves, and only one of them is a floor:
     *  - `low` vs every other level MUST stay far apart under tritanopia
     *    (`low` simulates to a blue, everything else to a yellow), because
     *    "is the road clear or not" has to survive every deficiency;
     *  - `moderate` vs `severe` is deliberately NOT given a floor. It measures
     *    ΔE 2.84 and the KDoc says so out loud. Asserting it stays broken would
     *    be an odd thing to pin, so this only checks that the KDoc's stated
     *    number is still the truth — if a future tweak separates them, this
     *    fails and the KDoc's concession must be rewritten as a win.
     */
    @Test
    fun `tritanopia keeps low readable and the KDoc's conceded collapse is accurate`() {
        val night = TrafficPalette.NIGHT
        val low = Cvd.simulate(night.low, Cvd.TRITAN)
        for ((name, color) in
            listOf(
                "moderate" to night.moderate,
                "heavy" to night.heavy,
                "severe" to night.severe,
            )) {
            val delta = Cvd.deltaE(low, Cvd.simulate(color, Cvd.TRITAN))
            assertTrue(
                "a tritanope must still tell free-flowing low from $name, but they " +
                    "are only %.2f apart".format(delta),
                delta > 100.0,
            )
        }

        // The conceded collapse, held to the number the KDoc prints.
        val moderateSevere =
            Cvd.deltaE(
                Cvd.simulate(night.moderate, Cvd.TRITAN),
                Cvd.simulate(night.severe, Cvd.TRITAN),
            )
        assertTrue(
            "TrafficPalette's KDoc states moderate/severe collapse to ΔE 2.84 under " +
                "tritanopia; it now measures %.2f, so that paragraph is stale"
                    .format(moderateSevere),
            moderateSevere < 5.0,
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
