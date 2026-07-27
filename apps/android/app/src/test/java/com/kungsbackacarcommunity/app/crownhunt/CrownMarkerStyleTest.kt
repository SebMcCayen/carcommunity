package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle
import com.kungsbackacarcommunity.app.incidents.IncidentPalette
import com.kungsbackacarcommunity.app.incidents.IncidentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Colour-vision-deficiency simulation, test-only.
 *
 * A local copy of the helper `TrafficPaletteTest` introduced (Viénot, Brettel &
 * Mollon 1999: gamma-expand sRGB, convert to LMS cone response, project onto the
 * plane the missing cone can no longer leave, convert back), because that one is
 * file-private. Separation is measured as CIE76 ΔE in L*a*b*, because after
 * simulation the two cues a dichromat still has — lightness and the blue-yellow
 * axis — are exactly the axes L*a*b* measures.
 *
 * A luminance RATIO would not do: it sees only one of those axes, which is
 * precisely how the traffic palette's original colour-blind claim came to be
 * wrong while its test still passed. Simulate the deficiency, then measure.
 */
private object Cvd {
    enum class Deficiency(val label: String) {
        DEUTAN("deuteranopia"),
        PROTAN("protanopia"),
        TRITAN("tritanopia"),
    }

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

        val l = 17.8824 * r + 43.5161 * g + 4.11935 * b
        val m = 3.45565 * r + 27.1554 * g + 3.86714 * b
        val s = 0.0299566 * r + 0.184309 * g + 1.46709 * b

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
 * How a crown is drawn, measured rather than asserted in a comment.
 *
 * [CrownMarkerStyle]'s KDoc makes specific, checkable claims — every tier is
 * ΔE ≥ 20 from every other under normal vision AND three simulated colour-vision
 * deficiencies; every glyph clears 4.5:1 on its own disc; only the legendary tier
 * glows. This file is what makes those claims true rather than aspirational.
 */
class CrownMarkerStyleTest {

    /**
     * Floor for pairwise separation between tiers, under normal vision and under
     * each simulated deficiency. The shipped palette's worst pair measures
     * ΔE 20.89 (tritan uncommon/rare), so 20.0 leaves a sliver of headroom while
     * still failing loudly on anything that collapses two tiers together.
     */
    private val minTierDeltaE = 20.0

    /** WCAG AA for normal text — the right bar for a glyph at map scale. */
    private val minGlyphContrast = 4.5

    // ---- Rarity → style, exhaustively -------------------------------------

    /**
     * Each tier gets its OWN silhouette. This is the load-bearing channel: it is
     * the one that survives any colour-vision deficiency, any night basemap, and
     * a 32 dp marker seen at a glance.
     */
    @Test
    fun everyRarityHasItsOwnGlyph() {
        val glyphs = CrownRarity.entries.map { CrownMarkerStyle.glyph(it) }
        assertEquals(
            "each tier must have a distinct silhouette",
            CrownRarity.entries.size,
            glyphs.toSet().size,
        )
        assertEquals(CrownMarkerStyle.Glyph.BAND, CrownMarkerStyle.glyph(CrownRarity.COMMON))
        assertEquals(
            CrownMarkerStyle.Glyph.JEWELLED_BAND,
            CrownMarkerStyle.glyph(CrownRarity.UNCOMMON),
        )
        assertEquals(CrownMarkerStyle.Glyph.FIVE_POINT, CrownMarkerStyle.glyph(CrownRarity.RARE))
        assertEquals(CrownMarkerStyle.Glyph.ROYAL, CrownMarkerStyle.glyph(CrownRarity.LEGENDARY))
    }

    /** Every glyph resolves to its own drawable — no tier borrows another's art. */
    @Test
    fun everyRarityResolvesToItsOwnDrawable() {
        val resIds = CrownRarity.entries.map { crownGlyphRes(it) }
        assertEquals(CrownRarity.entries.size, resIds.toSet().size)
        for ((rarity, res) in CrownRarity.entries.zip(resIds)) {
            assertNotEquals("$rarity has no drawable", 0, res)
        }
    }

    /** Each tier gets its own disc colour, and the wire values match the backend table. */
    @Test
    fun everyRarityHasItsOwnDiscColourAndItsOwnWireValue() {
        val discs = CrownRarity.entries.map { CrownMarkerStyle.discColorArgb(it) }
        assertEquals(CrownRarity.entries.size, discs.toSet().size)
        val wires = CrownRarity.entries.map { it.wire }
        assertEquals(listOf("common", "uncommon", "rare", "legendary"), wires)
        // The rarity table mirrored from CROWN_RARITY_TABLE. A drift here would
        // show a member the wrong number before the server corrected it.
        assertEquals(10, CrownRarity.COMMON.rewardPoints)
        assertEquals(25, CrownRarity.UNCOMMON.rewardPoints)
        assertEquals(100, CrownRarity.RARE.rewardPoints)
        assertEquals(500, CrownRarity.LEGENDARY.rewardPoints)
    }

    /** Round-tripping the wire value, including the unknown case. */
    @Test
    fun anUnknownRarityWireValueResolvesToNullRatherThanToCommon() {
        for (rarity in CrownRarity.entries) {
            assertEquals(rarity, CrownRarity.fromWire(rarity.wire))
        }
        assertNull(CrownRarity.fromWire("mythic"))
        assertNull(CrownRarity.fromWire(null))
        // Case matters: the backend writes lower-case, and a loose match would
        // let a malformed document render as a tier it is not.
        assertNull(CrownRarity.fromWire("Common"))
    }

    // ---- The colour claims ------------------------------------------------

    /**
     * The claim [CrownMarkerStyle]'s KDoc makes: every pair of tiers is plainly
     * different under normal vision AND under each of the three dichromacies.
     *
     * Measured with the deficiency SIMULATED and the difference taken in L*a*b*.
     * A luminance ratio would pass palettes that a deuteranope cannot separate at
     * all, which is how this class of bug ships.
     */
    @Test
    fun everyTierPairStaysSeparableUnderNormalVisionAndEveryDichromacy() {
        val tiers = CrownRarity.entries
        var worst = Double.MAX_VALUE
        var worstLabel = ""
        for (i in tiers.indices) {
            for (j in i + 1 until tiers.size) {
                val a = CrownMarkerStyle.discColorArgb(tiers[i])
                val b = CrownMarkerStyle.discColorArgb(tiers[j])
                val measurements =
                    buildList {
                        add("normal vision" to Cvd.deltaE(a, b))
                        for (deficiency in Cvd.Deficiency.entries) {
                            add(
                                deficiency.label to
                                    Cvd.deltaE(
                                        Cvd.simulate(a, deficiency),
                                        Cvd.simulate(b, deficiency),
                                    ),
                            )
                        }
                    }
                for ((label, delta) in measurements) {
                    if (delta < worst) {
                        worst = delta
                        worstLabel = "${tiers[i]}/${tiers[j]} under $label"
                    }
                    assertTrue(
                        "${tiers[i]} vs ${tiers[j]} under $label: ΔE %.2f, below the %.1f floor"
                            .format(delta, minTierDeltaE),
                        delta >= minTierDeltaE,
                    )
                }
            }
        }
        // Pins the documented worst case so an "improvement" that quietly makes
        // it worse still shows up in review.
        assertTrue(
            "worst measured pair is $worstLabel at ΔE %.2f — the KDoc claims ~20.9"
                .format(worst),
            worst < 22.0,
        )
    }

    /**
     * Every tier's glyph is readable on its own disc — which is exactly why the
     * glyph colour is COMPUTED per tier rather than fixed at white.
     */
    @Test
    fun everyGlyphIsReadableOnItsOwnDisc() {
        for (rarity in CrownRarity.entries) {
            val disc = CrownMarkerStyle.discColorArgb(rarity)
            val glyph = CrownMarkerStyle.glyphColorArgb(rarity)
            val contrast = IncidentMarkerStyle.contrastRatio(glyph, disc)
            assertTrue(
                "$rarity: glyph contrast is %.2f:1, below the %.1f:1 floor"
                    .format(contrast, minGlyphContrast),
                contrast >= minGlyphContrast,
            )
        }
    }

    /**
     * A fixed white glyph would FAIL the above on the pewter and gold discs, so
     * the per-tier computation is not decoration. Pinned rather than narrated.
     */
    @Test
    fun aFixedWhiteGlyphWouldBeUnreadableOnThePewterAndGoldDiscs() {
        for (rarity in listOf(CrownRarity.COMMON, CrownRarity.LEGENDARY)) {
            val whiteOnDisc =
                IncidentMarkerStyle.contrastRatio(
                    IncidentMarkerStyle.GLYPH_LIGHT,
                    CrownMarkerStyle.discColorArgb(rarity),
                )
            assertTrue(
                ("White on the $rarity disc measures %.2f:1 — if this ever clears " +
                    "$minGlyphContrast:1 the per-tier glyph colour is no longer needed.")
                    .format(whiteOnDisc),
                whiteOnDisc < minGlyphContrast,
            )
        }
    }

    /**
     * Only the legendary tier glows.
     *
     * A halo on every crown would be visual noise on a map that already carries
     * incidents, traffic and a route line — and would stop meaning anything.
     * Reserved for the 1%-weight tier, it says "that one is worth walking to"
     * from further away than the disc colour is readable.
     */
    @Test
    fun onlyTheLegendaryTierHasAHalo() {
        assertNotNull(CrownMarkerStyle.glowColorArgb(CrownRarity.LEGENDARY))
        for (rarity in listOf(CrownRarity.COMMON, CrownRarity.UNCOMMON, CrownRarity.RARE)) {
            assertNull("$rarity must not glow", CrownMarkerStyle.glowColorArgb(rarity))
        }
    }

    /**
     * The halo is TRANSLUCENT — the KDoc says ~35% alpha, and a solid ring this
     * wide would read as a second marker rather than a glow.
     */
    @Test
    fun theHaloIsTranslucentRatherThanASecondSolidRing() {
        val alpha = (CrownMarkerStyle.LEGENDARY_GLOW ushr 24) and 0xFF
        assertTrue("halo alpha is $alpha/255, not the documented ~35%", alpha in 80..100)
    }

    // ---- The honest gap against the incidents layer ------------------------

    /**
     * The two cross-layer pairs [CrownMarkerStyle]'s KDoc admits to, measured.
     *
     * They sit below the within-layer bar, and the KDoc says so and says why the
     * mitigation (different SHAPES on both layers, plus the legendary halo) is
     * enough. This test exists so the gap cannot silently WIDEN: a palette tweak
     * that pushed rare closer to `road_closed` would be caught here rather than
     * on a member's screen.
     */
    @Test
    fun theDocumentedCrossLayerGapsAreWhatTheKDocSaysTheyAre() {
        val rareVsRoadClosed =
            Cvd.deltaE(
                CrownMarkerStyle.discColorArgb(CrownRarity.RARE),
                IncidentPalette.colorArgb(IncidentType.ROAD_CLOSED),
            )
        assertTrue(
            "rare vs road_closed measures ΔE %.2f; the KDoc documents 16.7"
                .format(rareVsRoadClosed),
            rareVsRoadClosed > 15.0,
        )

        val legendaryVsHazard =
            Cvd.deltaE(
                CrownMarkerStyle.discColorArgb(CrownRarity.LEGENDARY),
                IncidentPalette.colorArgb(IncidentType.HAZARD),
            )
        assertTrue(
            "legendary vs hazard measures ΔE %.2f; the KDoc documents 19.0"
                .format(legendaryVsHazard),
            legendaryVsHazard > 17.0,
        )

        // And no OTHER cross-layer pair is allowed to join them undocumented.
        for (rarity in CrownRarity.entries) {
            for (type in IncidentType.entries) {
                val documented =
                    (rarity == CrownRarity.RARE && type == IncidentType.ROAD_CLOSED) ||
                        (rarity == CrownRarity.LEGENDARY && type == IncidentType.HAZARD)
                if (documented) continue
                val delta =
                    Cvd.deltaE(
                        CrownMarkerStyle.discColorArgb(rarity),
                        IncidentPalette.colorArgb(type),
                    )
                assertTrue(
                    ("$rarity vs $type measures ΔE %.2f — a NEW cross-layer collision. " +
                        "Either move the colour or document it in CrownMarkerStyle's KDoc.")
                        .format(delta),
                    delta >= minTierDeltaE,
                )
            }
        }
    }

    // ---- Geometry ---------------------------------------------------------

    /**
     * The marker is bigger than an incident badge, on purpose: one is a warning
     * you must not miss, the other a collectable you are meant to spot and go to.
     * The KDoc's arithmetic (28 dp disc + rings ≈ 35 dp; + halo ≈ 47 dp) is
     * pinned here so a retune cannot quietly break the "reads at ~32 dp" brief.
     */
    @Test
    fun theMarkerGeometryMatchesTheDocumentedSizes() {
        assertTrue(
            "a crown must be larger than an incident badge",
            CrownMarkerStyle.DISC_DIAMETER_DP > IncidentMarkerStyle.DISC_DIAMETER_DP,
        )
        val rings =
            2f * (IncidentMarkerStyle.RING_LIGHT_WIDTH_DP + IncidentMarkerStyle.RING_DARK_WIDTH_DP)
        val plain = CrownMarkerStyle.DISC_DIAMETER_DP + rings
        val legendary = plain + 2f * CrownMarkerStyle.GLOW_WIDTH_DP
        assertEquals(35f, plain, 0.01f)
        assertEquals(47f, legendary, 0.01f)
        assertTrue("the whole marker must clear the 32 dp brief", plain >= 32f)
        // A wide silhouette needs a tighter glyph fraction than a compact one, or
        // its points crowd the ring and the symbol becomes a smudge.
        assertTrue(
            "a crown's glyph fraction must be tighter than an incident's",
            CrownMarkerStyle.GLYPH_SCALE < IncidentMarkerStyle.GLYPH_SCALE,
        )
    }

    /**
     * Style-image names are namespaced and keyed on everything that changes the
     * pixels, so the crown layer can never reuse an incident's cached image and a
     * palette change produces a NEW image rather than a stale one.
     */
    @Test
    fun styleImageIdsAreNamespacedAndVaryWithEveryVisualInput() {
        val ids =
            CrownRarity.entries.map { rarity ->
                com.kungsbackacarcommunity.app.shell.CrownMarkerBitmaps.imageId(
                    iconRes = crownGlyphRes(rarity),
                    discColorArgb = CrownMarkerStyle.discColorArgb(rarity),
                    glyphColorArgb = CrownMarkerStyle.glyphColorArgb(rarity),
                    glowColorArgb = CrownMarkerStyle.glowColorArgb(rarity),
                )
            }
        assertEquals("one image per tier", CrownRarity.entries.size, ids.toSet().size)
        for (id in ids) {
            assertTrue("'$id' must be namespaced to the crown layer", id.startsWith("kcc-crown-"))
        }
        // The glow is part of the key: the same disc with and without a halo are
        // different pixels and must not share a cache entry.
        val withGlow =
            com.kungsbackacarcommunity.app.shell.CrownMarkerBitmaps.imageId(1, 2, 3, 4)
        val withoutGlow =
            com.kungsbackacarcommunity.app.shell.CrownMarkerBitmaps.imageId(1, 2, 3, null)
        assertNotEquals(withGlow, withoutGlow)
    }
}
