package com.kungsbackacarcommunity.app.shell

/**
 * The four congestion levels the Mapbox traffic tileset reports, plus the
 * fallback, as ARGB ints. Deliberately Mapbox-free so the palette is a plain
 * JVM value that CI can unit-test without a device, a token, or the style DSL —
 * only the `match` expression built FROM it needs the SDK.
 */
data class CongestionColors(
    val low: Int,
    val moderate: Int,
    val heavy: Int,
    val severe: Int,
    val unknown: Int,
)

/**
 * Congestion line colours + width per light preset for the traffic overlay.
 *
 * Day and night are two palettes, not one palette and a tint. The day colours
 * are mid-tone pigments chosen to read against a WHITE basemap; under the
 * Standard style's night `lightPreset` the whole map drops to near-black, and
 * against that the day `heavy` orange and `severe` deep red sit too close to the
 * basemap's own lightness to pick out — the "can't see where the traffic is at
 * night" complaint.
 *
 * The night palette is deliberately NOT a pastel lift of the day one. Seb's
 * brief is that congestion should read as DARK RED (heavy) and DARK YELLOW
 * (moderate) — saturated, road-map pigments rather than washed-out highlighter
 * colours — while still clearing the basemap by enough luminance to be seen.
 * The invariants TrafficPaletteTest pins:
 *
 *  - `heavy` is a dark red and `moderate` a dark yellow (red-dominant /
 *    red+green-dominant, and both darker than their day counterparts, which is
 *    what makes them "dark" rather than "lifted");
 *  - every night level still clears the night basemap's own lightness, so
 *    "dark" never degrades back into "invisible";
 *  - every pair of levels — all six, not just the obvious ones — stays at least
 *    ΔE 18 apart in CIE L*a*b* after being run through a simulated deuteranope
 *    AND a simulated protanope, so no two levels can be confused by a red-green
 *    colour-blind driver;
 *  - the day palette is byte-for-byte unchanged.
 *
 * Colour-blind note — what is actually true, and how it is measured:
 *
 * Under dichromacy the red/green axis collapses, so `low` (green) and the reds
 * cannot be told apart by hue. What survives is LIGHTNESS plus the blue-yellow
 * axis. `TrafficPaletteTest` therefore simulates the palette through the
 * Viénot-Brettel-Mollon 1999 transform and measures every pair in CIE L*a*b*,
 * which counts both surviving cues; raw luminance ratio alone does not, and
 * relying on it is how the previous, overstated version of this note went wrong.
 *
 * The enforced invariant is: **all six level pairs are at least ΔE 18 apart
 * under BOTH simulated deuteranopia and simulated protanopia.** The measured
 * worst pair is ΔE 20.94 (deutan `heavy`/`severe`); every other pair is wider.
 *
 * Two specific things this note previously got wrong, stated correctly:
 *  - `low` is NOT separated from `severe` by luminance. Under deuteranopia the
 *    two sit at a 1.07x luminance ratio — near-identical brightness. They are
 *    separated by CHROMA: `low` keeps a lifted blue channel while `severe` has
 *    almost none, giving ΔE 21.12. The "~2x luminance gap from the reds" claim
 *    only ever held for `low` vs `heavy`, not `low` vs `severe`.
 *  - `moderate` vs `heavy` is separated by luminance (~3.3x) AND chroma, so it
 *    is the most robust pair — but "survives any form of colour blindness" is
 *    broader than the tests check: tritanopia is deliberately NOT pinned, since
 *    the blue-yellow deficiency is rare and the ramp is built on exactly that
 *    axis. Tritanopes retain the red/green cue that dichromats lose.
 */
object TrafficPalette {
    /** Original palette — tuned for the bright basemap. Do not change casually. */
    val DAY =
        CongestionColors(
            low = 0xFF4CAF50.toInt(),
            moderate = 0xFFFFC107.toInt(),
            heavy = 0xFFFF6F00.toInt(),
            severe = 0xFFD32F2F.toInt(),
            unknown = 0xFF9E9E9E.toInt(),
        )

    /**
     * Tuned for the near-black night basemap: saturated, deep pigments that stay
     * well clear of the background without bleaching into highlighter colours.
     *
     * - `low`   emerald green — free flow; deliberately the least
     *   attention-grabbing of the four, and dimmer than `moderate`. Its blue
     *   channel is lifted (a cool green rather than a grass green) because that
     *   blue is the ONLY cue separating it from `severe` for a dichromat.
     * - `moderate` dark yellow (mustard/ochre) — bright enough to spot at a
     *   glance, which is the level users scan for.
     * - `heavy` dark red — the requested "dark red", ~3.3x darker than moderate
     *   so the two never blur together.
     * - `severe` a hotter, brighter red so the WORST level is the most salient
     *   thing on the map; it is the one exception to "darker at night".
     * - `unknown` dark grey — present but ignorable.
     *
     * `low`, `heavy` and `severe` are retuned from the first cut of this ramp:
     * the original trio left `low`/`severe` at ΔE 17.91 (protan) and
     * `heavy`/`severe` at ΔE 16.24 — close enough for a red-green colour-blind
     * driver to misread a jam. Basemap contrast was not traded away to get
     * there; `heavy` clears the basemap slightly better than before (4.77x, was
     * 4.58x).
     */
    val NIGHT =
        CongestionColors(
            low = 0xFF11B076.toInt(),
            moderate = 0xFFC9A227.toInt(),
            heavy = 0xFFC20017.toInt(),
            severe = 0xFFE65656.toInt(),
            unknown = 0xFF757575.toInt(),
        )

    /** Line width (px) of the congestion layer for the day basemap. */
    const val DAY_LINE_WIDTH: Double = 2.5

    /**
     * Line width (px) at night. Wider than [DAY_LINE_WIDTH]: the night basemap's
     * own roads are dim, so a hairline of colour has less surrounding contrast to
     * borrow from and needs more area to register.
     */
    const val NIGHT_LINE_WIDTH: Double = 3.5

    /** The congestion colours for [mode]. */
    fun colors(mode: MapMode): CongestionColors = if (mode == MapMode.Night) NIGHT else DAY

    /** The congestion line width for [mode]. */
    fun lineWidth(mode: MapMode): Double =
        if (mode == MapMode.Night) NIGHT_LINE_WIDTH else DAY_LINE_WIDTH

    /**
     * WCAG relative luminance (0 = black, 1 = white) of an ARGB colour, ignoring
     * alpha. The standard sRGB formula: gamma-expand each channel, then weight
     * by human sensitivity (green dominates, blue barely registers) — which is
     * why "is this brighter?" cannot be answered by comparing raw RGB numbers.
     * Exposed so the palette's contrast claim is checked against the real
     * perceptual measure rather than by eye.
     */
    fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val raw = ((argb shr shift) and 0xFF) / 255.0
            return if (raw <= 0.03928) raw / 12.92 else Math.pow((raw + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
