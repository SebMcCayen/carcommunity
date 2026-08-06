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
 * TWO cross-layer pairs sit below the ΔE 20 bar the crown tiers hold against
 * each other, and both are stated rather than hidden because the mitigation is
 * the same and is real:
 *
 *  - [RARE]'s indigo against the incidents layer's purple `road_closed` — ΔE 16.7;
 *  - [LEGENDARY]'s gold against the amber `hazard` disc — ΔE 19.0.
 *
 * In both cases the two markers are different SHAPES — a crown against a barred
 * circle, a crown against a warning triangle — and shape is the primary channel
 * on BOTH layers, so the pair a user must tell apart at a glance is distinguished
 * by the strongest cue available. Legendary additionally carries the only halo in
 * either palette. Pushing rare away from purple would collide it with the police
 * blue, and pushing legendary away from amber would stop it reading as gold at
 * all, which is the one thing the top tier has to do; the palette has four
 * incident hues and four crown tiers to fit around them, and these are the
 * least-bad seats. `CrownMarkerStyleTest` measures both numbers, so the gap
 * cannot silently widen.
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
     * Disc colour for a HAND-PLACED admin Kronjakt point (`crownHuntPoints`), as
     * opposed to an auto-spawned crown.
     *
     * These are the curated, safety-approved, permanent reward points an admin
     * creates in the portal — a different SOURCE from the rarity-tiered spawns,
     * so they get their OWN disc rather than borrowing a tier's: a member must be
     * able to tell "an official reward point an admin placed here" from "an
     * ephemeral pickup the engine dropped". A deep royal magenta, distinct in
     * L*a*b* from all four rarity discs under normal vision and under simulated
     * protanopia/deuteranopia/tritanopia (measured in `CrownMarkerStyleTest`),
     * carrying the ROYAL silhouette and no glow (the glow stays reserved for the
     * legendary spawn tier).
     */
    const val ADMIN_POINT_DISC: Int = 0xFFB0136A.toInt()

    /**
     * The disc colour for a crown the member is OUT of collect range of.
     *
     * A crown is only collectable when the device is within its collect radius
     * (see [CrownCollectGate]); until then its rarity colour would be a promise
     * the map cannot keep — "walk to that legendary" reads the same at 5 km as at
     * 5 m. So an out-of-range crown is drawn in a single flat, DESATURATED slate:
     * still clearly a crown (the silhouette is untouched — rarity survives in the
     * primary channel), but visibly inactive, so a member learns at a glance which
     * crowns are actually reachable right now and the colour "lights up" to the
     * rarity hue the moment they are in range.
     *
     * Deliberately DARKER and flatter than the [CrownRarity.COMMON] pewter
     * (`0xFF8E9AA6`) so "out of range" never reads as "a common crown": a member
     * standing on a common crown sees pewter, and a distant one sees this slate,
     * and the two must not be confused. Neutral (near-zero chroma) so it cannot be
     * mistaken for any rarity hue either.
     */
    const val OUT_OF_RANGE_DISC: Int = 0xFF5F6368.toInt()

    /**
     * The disc colour to actually draw a spawned crown with: its rarity colour
     * when [inRange], the neutral [OUT_OF_RANGE_DISC] slate otherwise. The ONE
     * place the in-range → colour rule lives, so the map layer and any preview
     * agree and a test pins it.
     */
    fun discColorArgb(rarity: CrownRarity, inRange: Boolean): Int =
        if (inRange) discColorArgb(rarity) else OUT_OF_RANGE_DISC

    /** The disc for an admin point: its royal magenta in range, slate otherwise. */
    fun adminPointDiscArgb(inRange: Boolean): Int =
        if (inRange) ADMIN_POINT_DISC else OUT_OF_RANGE_DISC

    /**
     * The glyph tint for a spawned crown, following [discColorArgb] so an
     * out-of-range crown's silhouette stays legible against the slate disc.
     */
    fun glyphColorArgb(rarity: CrownRarity, inRange: Boolean): Int =
        if (inRange) glyphColorArgb(rarity) else outOfRangeGlyphColorArgb()

    /** The glyph tint for an admin point, in or out of range. */
    fun adminPointGlyphColorArgb(inRange: Boolean): Int =
        if (inRange) adminPointGlyphColorArgb() else outOfRangeGlyphColorArgb()

    /**
     * The glow for a spawned crown: only [CrownRarity.LEGENDARY] glows, and only
     * while IN range — an out-of-range crown carries no halo, so the "walk to that
     * one" cue is reserved for a legendary the member can actually reach.
     */
    fun glowColorArgb(rarity: CrownRarity, inRange: Boolean): Int? =
        if (inRange) glowColorArgb(rarity) else null

    /**
     * The glyph colour for the out-of-range slate disc — the same computed
     * contrast choice [glyphColorArgb] and [adminPointGlyphColorArgb] make, so a
     * retune of [OUT_OF_RANGE_DISC] cannot leave an unreadable silhouette behind.
     */
    fun outOfRangeGlyphColorArgb(): Int {
        val light =
            IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.GLYPH_LIGHT, OUT_OF_RANGE_DISC)
        val dark =
            IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.GLYPH_DARK, OUT_OF_RANGE_DISC)
        return if (light >= dark) IncidentMarkerStyle.GLYPH_LIGHT else IncidentMarkerStyle.GLYPH_DARK
    }

    /**
     * The glyph colour for the admin-point disc — whichever of
     * [IncidentMarkerStyle.GLYPH_LIGHT] / [IncidentMarkerStyle.GLYPH_DARK]
     * contrasts better, chosen the same computed way as [glyphColorArgb] so a
     * retune of [ADMIN_POINT_DISC] cannot leave an unreadable glyph behind.
     */
    fun adminPointGlyphColorArgb(): Int {
        val light =
            IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.GLYPH_LIGHT, ADMIN_POINT_DISC)
        val dark =
            IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.GLYPH_DARK, ADMIN_POINT_DISC)
        return if (light >= dark) IncidentMarkerStyle.GLYPH_LIGHT else IncidentMarkerStyle.GLYPH_DARK
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
