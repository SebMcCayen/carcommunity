package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.shell.TrafficPalette

/**
 * How an incident is drawn on the map: one **icon per category**, on a coloured
 * disc, inside a two-tone ring.
 *
 * Deliberately Android-free (plain ARGB ints and enums, no `Color`, no
 * `Drawable`, no Mapbox) for the same reason [TrafficPalette] is: the legibility
 * claims below are then *measured by JVM unit tests in CI* rather than asserted
 * in a comment and verified by eye on one device in one lighting condition.
 *
 * ## Why icons and not coloured circles
 *
 * Incidents used to render as plain coloured circles, so **colour was the only
 * channel** carrying the category: a red dot and an orange dot differ by hue
 * alone, and to a red-green colour-blind user (~8% of men) they frequently do
 * not differ at all. That is the same failure PR #474 fixed for the night
 * traffic palette, and the fix here is the stronger version of it — the glyph
 * carries the meaning, so the category survives *any* colour confusion, and the
 * colour becomes reinforcement rather than the message. Two users looking at the
 * same roadworks marker read "roadworks" from the same shape.
 *
 * ## Why one icon set serves BOTH light and dark maps
 *
 * The obvious approach — a day icon set and a night icon set — is a trap on this
 * surface. The main map does not reload its style for day/night; it flips the
 * Standard basemap's `lightPreset` import config, and that re-lights **only the
 * basemap**. App-owned layers do not follow it (see `MapboxMapSurface`'s traffic
 * re-colour, which exists precisely because of this). A day-tuned icon would
 * therefore sit unchanged on a night map until something else happened to redraw
 * it.
 *
 * So the marker is built to be mode-independent instead, via the ring:
 *
 * - a **[RING_LIGHT] inner ring** separates the marker from a DARK basemap
 *   (measured 16.9:1 against a representative night basemap), and
 * - a **[RING_DARK] outer hairline** separates it from a LIGHT basemap
 *   (measured 14.0:1 against a representative day basemap).
 *
 * Whichever way the map is lit, one of the two rings is doing the work and the
 * other is nearly invisible — which is exactly what makes a single asset legible
 * in both. `IncidentMarkerStyleTest` pins this: for each basemap the better ring
 * must clear 3:1, so the property cannot silently regress.
 *
 * ## Glyph colour
 *
 * The glyph is NOT uniformly white. White reads well on the red/blue/purple
 * discs but collapses on amber (1.7:1, unreadable) and orange (2.7:1). Each
 * category therefore takes whichever of [GLYPH_LIGHT]/[GLYPH_DARK] contrasts
 * better with its own disc, and the test pins every category at >= 4.5:1
 * (WCAG AA for non-large text — the stricter of the plausible bars, since a
 * glyph at map scale is small).
 */
object IncidentMarkerStyle {
    /**
     * The glyph drawn inside the disc, one per [IncidentType].
     *
     * A separate enum from [IncidentType] rather than a drawable id on the type
     * itself, so the incidents *model* stays free of resource references and the
     * mapping is exhaustive-when-compiled.
     */
    enum class Glyph {
        /** Two colliding vehicles. */
        ACCIDENT,

        /** Roadworks barrier/cone. */
        ROADWORK,

        /** Exclamation in a triangle — the generic "something on the road". */
        HAZARD,

        /** Police shield/checkpoint. */
        POLICE,

        /** A barred circle — the road is shut. */
        ROAD_CLOSED,
    }

    /** Light glyph, for the dark discs. */
    const val GLYPH_LIGHT: Int = 0xFFFFFFFF.toInt()

    /**
     * Dark glyph, for the light discs (amber/orange). Deliberately a soft ink
     * rather than pure black: at marker scale pure black on saturated amber
     * shimmers, and this still measures 10.5:1.
     */
    const val GLYPH_DARK: Int = 0xFF1A1A1A.toInt()

    /** Inner ring — carries separation on a NIGHT basemap. */
    const val RING_LIGHT: Int = 0xFFFFFFFF.toInt()

    /** Outer hairline — carries separation on a DAY basemap. */
    const val RING_DARK: Int = 0xFF1A1A1A.toInt()

    /** Disc diameter in dp, before the rings. */
    const val DISC_DIAMETER_DP: Float = 26f

    /** Width of the [RING_LIGHT] inner ring, in dp. */
    const val RING_LIGHT_WIDTH_DP: Float = 2.5f

    /** Width of the [RING_DARK] outer hairline, in dp. */
    const val RING_DARK_WIDTH_DP: Float = 1f

    /**
     * Glyph size as a fraction of the disc diameter. Leaves a margin so the
     * glyph never touches the ring, which is what makes it read as a symbol on a
     * badge rather than a smudge.
     */
    const val GLYPH_SCALE: Float = 0.58f

    /**
     * Representative luminance reference for the Standard basemap's DAY light
     * preset — the pale road/landcover fill a marker most often sits on. Used
     * only to measure the ring claim in tests.
     */
    const val DAY_BASEMAP_REFERENCE: Int = 0xFFE8E6E1.toInt()

    /** As [DAY_BASEMAP_REFERENCE], for the NIGHT preset. */
    const val NIGHT_BASEMAP_REFERENCE: Int = 0xFF1B1D22.toInt()

    /** The glyph for [type]. Exhaustive, so a new category cannot be forgotten. */
    fun glyph(type: IncidentType): Glyph =
        when (type) {
            IncidentType.ACCIDENT -> Glyph.ACCIDENT
            IncidentType.ROADWORK -> Glyph.ROADWORK
            IncidentType.HAZARD -> Glyph.HAZARD
            IncidentType.POLICE -> Glyph.POLICE
            IncidentType.ROAD_CLOSED -> Glyph.ROAD_CLOSED
        }

    /**
     * The glyph colour for [type] — whichever of [GLYPH_LIGHT]/[GLYPH_DARK]
     * contrasts better with that category's disc.
     *
     * Computed rather than tabulated so that changing a disc colour in
     * [IncidentPalette] cannot leave an unreadable glyph behind: the choice
     * follows the colour automatically, and the test still enforces the floor.
     */
    fun glyphColorArgb(type: IncidentType): Int {
        val disc = IncidentPalette.colorArgb(type)
        return if (contrastRatio(GLYPH_LIGHT, disc) >= contrastRatio(GLYPH_DARK, disc)) {
            GLYPH_LIGHT
        } else {
            GLYPH_DARK
        }
    }

    /**
     * WCAG relative-contrast ratio between two opaque colours (1.0 identical,
     * 21.0 black-on-white). Built on [TrafficPalette.relativeLuminance] so the
     * app has ONE luminance implementation, not two that can disagree.
     */
    fun contrastRatio(argbA: Int, argbB: Int): Double {
        val a = TrafficPalette.relativeLuminance(argbA)
        val b = TrafficPalette.relativeLuminance(argbB)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * The best contrast either ring achieves against [basemapArgb] — i.e. how
     * well the marker's edge separates from that basemap. The two rings are
     * complementary by design, so this is a max, not a sum.
     */
    fun ringSeparation(basemapArgb: Int): Double =
        maxOf(
            contrastRatio(RING_LIGHT, basemapArgb),
            contrastRatio(RING_DARK, basemapArgb),
        )
}
