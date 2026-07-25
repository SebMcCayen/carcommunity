package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle

/**
 * How a crown is drawn on the map: a crown glyph on a rarity-coloured disc,
 * inside the same two-tone ring the incident badges use, with a glow reserved
 * for the legendary tier.
 *
 * Android-free on purpose — plain ARGB ints and enums, no `Color`, no
 * `Drawable`, no Mapbox — for exactly the reason
 * [IncidentMarkerStyle] is: the legibility claims below are then MEASURED by
 * JVM tests in CI, not asserted in a comment and checked by eye once.
 *
 * ## Two independent channels, and which one carries what
 *
 * **Silhouette carries rarity.** Each tier has its own crown outline — a plain
 * band, a banded crown with a centre stone, a five-point crown, and a five-point
 * crown with an orb finial. That is the channel that survives any colour-vision
 * deficiency, any night basemap, and a 32 dp marker seen at a glance.
 *
 * **Colour reinforces it.** The four discs are separated by CIE76 ΔE >= 20 from
 * each other under normal vision AND under simulated protanopia, deuteranopia
 * and tritanopia (`CrownMarkerStyleTest`, worst measured pair 20.89 — tritan
 * uncommon/rare). A luminance ratio would not have caught that; the test
 * simulates the deficiency and measures in L*a*b*, following the method
 * `TrafficPaletteTest` established.
 *
 * ## The honest gap: crowns versus incidents
 *
 * [RARE]'s indigo disc measures ΔE 16.7 from the incidents layer's purple
 * `road_closed` disc, which is below the bar the crown tiers hold against each
 * other. Stated rather than hidden, because the mitigation is real and
 * deliberate: the two are different SHAPES — a crown against a barred circle —
 * and shape is the primary channel on both layers, so the pair a user must tell
 * apart at a glance is distinguished by the strongest cue available. Pushing
 * rare away from purple would collide it with the police blue instead; the
 * palette has four incident hues and four crown tiers to fit around them, and
 * this is the least-bad seat.
 *
 * ## Why one icon set serves BOTH day and night
 *
 * The map does not reload its style for day/night — it flips the Standard
 * basemap's `lightPreset`, which re-lights only the basemap. App-owned images do
 * not follow. So, exactly as with the incident badges, the marker is built
 * mode-independent instead: [IncidentMarkerStyle.RING_LIGHT] separates it from a
 * dark basemap and [IncidentMarkerStyle.RING_DARK] from a light one, and
 * whichever way the map is lit one of the two is doing the work. The ring
 * constants are REUSED rather than redeclared so the two layers cannot drift
 * into looking like they come from different apps.
 */
object CrownMarkerStyle {
    /**
     * The crown silhouette drawn inside the disc, one per [CrownRarity].
     *
     * A separate enum from [CrownRarity] rather than a drawable id on the tier
     * itself, so the crown-spawn MODEL stays free of resource references and the
     * mapping is exhaustive-when-compiled — a fifth tier could not be added
     * without the compiler demanding art for it.
     */
    enum class Glyph {
        /** Plain three-point band — the everyday crown. */
        BAND,

        /** Three-point band with a raised centre stone. */
        JEWELLED_BAND,

        /** Five-point crown with stones along the band. */
        FIVE_POINT,

        /** Five-point crown on a jewelled band, topped with an orb. */
        ROYAL,
    }

    /** Disc colours, one per tier. See the ΔE claim in this object's KDoc. */
    fun discColorArgb(rarity: CrownRarity): Int =
        when (rarity) {
            // Pewter — reads as "ordinary metal" without being a dead grey.
            CrownRarity.COMMON -> 0xFF8E9AA6.toInt()
            // Green. Distant from every incident hue, so the cheapest crown a
            // user sees most often is never mistaken for a road warning.
            CrownRarity.UNCOMMON -> 0xFF2E7D32.toInt()
            // Indigo. See the "honest gap" note about `road_closed` purple.
            CrownRarity.RARE -> 0xFF4527A0.toInt()
            // Gold, and the only tier with a glow.
            CrownRarity.LEGENDARY -> 0xFFC79000.toInt()
        }

    /** The silhouette for [rarity]. Exhaustive, so a new tier cannot be forgotten. */
    fun glyph(rarity: CrownRarity): Glyph =
        when (rarity) {
            CrownRarity.COMMON -> Glyph.BAND
            CrownRarity.UNCOMMON -> Glyph.JEWELLED_BAND
            CrownRarity.RARE -> Glyph.FIVE_POINT
            CrownRarity.LEGENDARY -> Glyph.ROYAL
        }

    /**
     * The glyph colour for [rarity] — whichever of
     * [IncidentMarkerStyle.GLYPH_LIGHT] / [IncidentMarkerStyle.GLYPH_DARK]
     * contrasts better with that tier's disc.
     *
     * Computed rather than tabulated, for the same reason the incidents layer
     * computes it: retuning a disc colour then cannot leave an unreadable glyph
     * behind, because the choice follows the colour and the test still enforces
     * the 4.5:1 floor. (Pewter and gold take the dark glyph; green and indigo
     * take the light one.)
     */
    fun glyphColorArgb(rarity: CrownRarity): Int {
        val disc = discColorArgb(rarity)
        val light = IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.GLYPH_LIGHT, disc)
        val dark = IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.GLYPH_DARK, disc)
        return if (light >= dark) IncidentMarkerStyle.GLYPH_LIGHT else IncidentMarkerStyle.GLYPH_DARK
    }

    /**
     * The soft outer halo colour for [rarity], or null for tiers that have none.
     *
     * Only [CrownRarity.LEGENDARY] glows. A glow on every crown would be visual
     * noise on a map that already carries incidents, traffic and a route line,
     * and would stop meaning anything; reserved for the 1%-weight tier it says
     * "that one is worth walking to" at a glance, from further away than the
     * disc colour is readable.
     *
     * The alpha is baked into the constant rather than applied at draw time so
     * the value the test measures is the value that reaches the canvas.
     */
    fun glowColorArgb(rarity: CrownRarity): Int? =
        when (rarity) {
            CrownRarity.LEGENDARY -> LEGENDARY_GLOW
            CrownRarity.COMMON, CrownRarity.UNCOMMON, CrownRarity.RARE -> null
        }

    /**
     * Legendary halo: a warm gold at ~35% alpha. Translucent so the basemap and
     * anything under it still read through — a solid ring this wide would be a
     * second marker rather than a glow.
     */
    const val LEGENDARY_GLOW: Int = 0x59FFD24A

    /**
     * Disc diameter in dp.
     *
     * Larger than the incident badge's 26 dp, which is the difference between a
     * warning you must not miss and a collectable you are meant to spot and go
     * to. The brief's "must read at ~32 dp" is the WHOLE marker: 28 dp of disc
     * plus the two rings lands at ~35 dp for a plain crown, and the glow takes
     * legendary to ~47 dp.
     */
    const val DISC_DIAMETER_DP: Float = 28f

    /** Width of the legendary halo outside the rings, in dp. */
    const val GLOW_WIDTH_DP: Float = 6f

    /**
     * Glyph size as a fraction of the disc. Slightly tighter than the incidents'
     * 0.58 because a crown is a WIDE silhouette — at the same fraction its
     * points would crowd the ring, which is what turns a symbol into a smudge.
     */
    const val GLYPH_SCALE: Float = 0.54f
}
