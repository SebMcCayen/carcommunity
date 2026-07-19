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
 * Standard style's night `lightPreset` the whole map drops to dark greys, and
 * against that the day `moderate` amber and `severe` deep red sit too close to
 * the basemap's own lightness to pick out — which is exactly the "can't see
 * where the traffic is at night" complaint. The night palette keeps the same
 * hues, so the layer still reads green → yellow → orange → red at a glance, but
 * lifts every level clear of a dark background.
 *
 * The invariant this promises, and that TrafficPaletteTest pins directly:
 * every night colour is LIGHTER (higher relative luminance) than its day
 * counterpart, and the day palette is byte-for-byte unchanged.
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

    /** Lifted for the dark basemap; same hues, higher lightness. */
    val NIGHT =
        CongestionColors(
            low = 0xFF66BB6A.toInt(),
            moderate = 0xFFFFE04D.toInt(),
            heavy = 0xFFFF9E3D.toInt(),
            severe = 0xFFFF5C5C.toInt(),
            unknown = 0xFFBDBDBD.toInt(),
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
