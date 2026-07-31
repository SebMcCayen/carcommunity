package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.isValidWgs84Coordinate
import java.util.Locale

/**
 * The single source of truth for the app's **shareable location link** — the
 * RFC 5870 `geo:` URI the map's "Copy position" action writes to the clipboard
 * and the chat renderer detects and turns into a tappable "show on map" chip.
 *
 * One place holds the format (the regex + the build/parse functions) so the
 * clipboard writer and the chat detector can never disagree about what a valid
 * token looks like, and so the whole thing is JVM-unit-testable off-Compose.
 *
 * ## The format
 * `geo:<lat>,<lng>` — decimal degrees, WGS-84, latitude first (the `geo:` URI
 * convention, which is the OPPOSITE of Mapbox's lng-first [LatLng]; the
 * conversion is done here so no caller has to remember it). Coordinates are
 * rounded to [COORDINATE_DECIMALS] on write (≈1 m — precise enough to point at a
 * spot, coarse enough not to leak a doorstep). A human `📍 ` prefix may be added
 * for readability in a client that does not linkify; it sits OUTSIDE the token,
 * so detection is unaffected.
 *
 * ## Validation
 * [parse] rejects anything that is not a real coordinate: out-of-range lat/lng
 * (the shared [isValidWgs84Coordinate] gate), a non-finite number, or absurd
 * precision (more than [MAX_PARSE_DECIMALS] fractional digits — a garbage token,
 * not a place). A rejected token is simply not a match, so chat renders it as
 * plain text and the map is never moved to a bogus point.
 */
data class GeoLink(
    val latitude: Double,
    val longitude: Double,
)

/** A [GeoLink] found in a longer string, welded to the exact [range] it occupies. */
data class GeoLinkMatch(
    val range: IntRange,
    val link: GeoLink,
)

object GeoLinks {
    /** Fractional digits kept when WRITING a link (≈1.1 m at the equator). */
    const val COORDINATE_DECIMALS: Int = 5

    /**
     * The most fractional digits a token may carry and still PARSE. Beyond this
     * the precision is nonsensical (sub-millimetre) and the token is treated as
     * garbage rather than a place — it renders as plain text.
     */
    const val MAX_PARSE_DECIMALS: Int = 9

    /**
     * Matches a `geo:lat,lng` token. The integer part is capped at three digits
     * (no real latitude/longitude needs more), which also stops it from swallowing
     * a runaway number. Any altitude / `;`-parameters an RFC 5870 URI may carry
     * are deliberately NOT captured, so they are ignored rather than rejected. A
     * preceding letter/digit is excluded so `ageo:1,2` is not mistaken for a link.
     */
    private val TOKEN =
        Regex("""(?<![A-Za-z0-9])geo:(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")

    /** The bare `geo:lat,lng` token for [latitude]/[longitude], rounded for sharing. */
    fun format(latitude: Double, longitude: Double): String =
        "geo:${round(latitude)},${round(longitude)}"

    /**
     * A human-readable "lat, lng" for showing the picked point in the place menu —
     * the same rounding as [format] but without the `geo:` scheme, so it reads as a
     * coordinate rather than a link.
     */
    fun coordinateLabel(latitude: Double, longitude: Double): String =
        "${round(latitude)}, ${round(longitude)}"

    /**
     * The clipboard payload: the [format] token with a leading 📍 so it reads as a
     * place even in an app that does not linkify `geo:`. The token itself is
     * unchanged, so [findAll] still detects it.
     */
    fun formatForClipboard(latitude: Double, longitude: Double): String =
        "📍 ${format(latitude, longitude)}"

    /**
     * Parses a single `geo:lat,lng` [token] into a validated [GeoLink], or null
     * when it is not a well-formed, in-range, sensibly-precise coordinate.
     */
    fun parse(token: String): GeoLink? {
        val match = TOKEN.matchEntire(token.trim()) ?: return null
        return linkOf(match.groupValues[1], match.groupValues[2])
    }

    /**
     * Every valid `geo:` link in [text], in order, each with the exact character
     * range it occupies (the whole `geo:…` token, so a renderer replaces all of
     * it). Invalid tokens are skipped, so they survive as plain text. Returns an
     * empty list when there is nothing to find — the common case, kept cheap.
     */
    fun findAll(text: String): List<GeoLinkMatch> {
        if (!text.contains("geo:")) return emptyList()
        return TOKEN.findAll(text).mapNotNull { match ->
            val link = linkOf(match.groupValues[1], match.groupValues[2]) ?: return@mapNotNull null
            GeoLinkMatch(range = match.range, link = link)
        }.toList()
    }

    private fun linkOf(latText: String, lngText: String): GeoLink? {
        if (fractionDigits(latText) > MAX_PARSE_DECIMALS) return null
        if (fractionDigits(lngText) > MAX_PARSE_DECIMALS) return null
        val lat = latText.toDoubleOrNull() ?: return null
        val lng = lngText.toDoubleOrNull() ?: return null
        // WGS-84 range + finiteness, the same gate every user-placed point uses.
        if (!isValidWgs84Coordinate(LatLng(longitude = lng, latitude = lat))) return null
        return GeoLink(latitude = lat, longitude = lng)
    }

    private fun fractionDigits(number: String): Int {
        val dot = number.indexOf('.')
        return if (dot < 0) 0 else number.length - dot - 1
    }

    private fun round(value: Double): String =
        String.format(Locale.US, "%.${COORDINATE_DECIMALS}f", value)
}
