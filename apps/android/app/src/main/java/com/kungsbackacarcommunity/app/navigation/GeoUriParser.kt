package com.kungsbackacarcommunity.app.navigation

import java.net.URLDecoder

/**
 * What an incoming `geo:` / `google.navigation:` map link resolved to.
 *
 * The link is chosen from Android's "Open with" / default-handler chooser when
 * another app (a browser, a chat, a share sheet) fires an ACTION_VIEW for a map
 * URI and the member picked KCC. [GeoUriParser] decodes it into one of these so
 * the Activity never touches parsing and the whole thing stays JVM-testable.
 */
sealed interface GeoUriTarget {
    /**
     * A concrete, validated destination: a WGS-84 [point] and an optional human
     * [label] (the `(Name)` a Google-style `q=lat,lng(Name)` link carries).
     * This is the case the shell drives straight into its in-app navigate-here
     * preview.
     */
    data class Point(
        val point: LatLng,
        val label: String?,
    ) : GeoUriTarget

    /**
     * A free-text place query (`geo:0,0?q=Storgatan 1, Kungsbacka`) that carries
     * no coordinate. Modelled explicitly — rather than folded into null — so the
     * distinction is testable, but the shell currently IGNORES it (see the
     * consumer in MainActivity): geocoding an arbitrary address from a deep link
     * is out of scope, and dropping it is the honest degrade (the chooser still
     * offered a real maps app alongside KCC). Kept as a typed result so wiring it
     * to the address search later is a one-line change with no re-parse.
     */
    data class Query(
        val text: String,
    ) : GeoUriTarget
}

/**
 * Pure, Android-free parser for the incoming map-link URIs KCC registers as a
 * handler for (see the `geo` / `google.navigation` intent-filter on
 * MainActivity). This is the ONLY place that decodes them, so the Activity holds
 * no parsing logic and every real-world form is exercised in a JVM unit test
 * with no device.
 *
 * ## Forms handled
 * - `geo:LAT,LNG` — a bare RFC 5870 point (an optional third `,alt` is ignored).
 * - `geo:LAT,LNG?z=..` — the same, with a zoom (or any other) query parameter
 *   that is simply not consulted.
 * - `geo:0,0?q=LAT,LNG` — the Google convention where the path is a placeholder
 *   map centre and the real destination rides in `q`.
 * - `geo:0,0?q=LAT,LNG(Label)` — the same, plus a human label used as the pin's
 *   name.
 * - `geo:0,0?q=<address>` — a free-text place; returned as [GeoUriTarget.Query]
 *   (the consumer ignores it, see that type's doc).
 * - `google.navigation:q=LAT,LNG` / `google.navigation:q=<address>` — the
 *   turn-by-turn deep link some apps emit; parsed via the same `q` rules.
 *
 * ## Precedence
 * When a link carries BOTH a path coordinate and a `q=lat,lng`, the `q`
 * coordinate wins — that is the Google convention (the path is only the map
 * centre). A bare `geo:0,0` with no usable `q` is the "no location" sentinel and
 * resolves to null rather than to the middle of the Gulf of Guinea.
 *
 * ## Validation
 * Every coordinate is run through the shared [isValidWgs84Coordinate] gate, so a
 * malformed, non-finite, or out-of-range value is rejected (yielding null, or
 * falling through to the free-text [GeoUriTarget.Query] branch when the text was
 * never a coordinate to begin with).
 */
object GeoUriParser {

    /**
     * Resolves [raw] to a [GeoUriTarget], or null when it is not a map link we
     * handle or carries no usable destination.
     */
    fun parse(raw: String?): GeoUriTarget? {
        val uri = raw?.trim().orEmpty()
        if (uri.isEmpty()) return null
        val schemeSep = uri.indexOf(':')
        if (schemeSep <= 0) return null
        val scheme = uri.substring(0, schemeSep).lowercase()
        val rest = uri.substring(schemeSep + 1)
        return when (scheme) {
            "geo" -> parseGeo(rest)
            // The turn-by-turn deep link is all query, no path — its whole
            // payload is the `q=` parameter, so route it through the same
            // q-handling as a geo: link.
            "google.navigation" -> fromQueryValue(paramsOf(rest)["q"])
            else -> null
        }
    }

    /** `<path>[?<query>]` after the `geo:` scheme. */
    private fun parseGeo(rest: String): GeoUriTarget? {
        val qIndex = rest.indexOf('?')
        val path = if (qIndex < 0) rest else rest.substring(0, qIndex)
        val query = if (qIndex < 0) "" else rest.substring(qIndex + 1)
        val qValue = paramsOf(query)["q"]

        // Google convention: a `q` coordinate is the authoritative destination
        // and wins over the path (which is only the map centre, usually 0,0).
        if (qValue != null) {
            fromQueryValue(qValue)?.let { target ->
                // A q coordinate is authoritative; a q free-text query is only
                // meaningful when the path itself is not a real place.
                if (target is GeoUriTarget.Point) return target
                if (pointFromPath(path) == null) return target
            }
        }

        // No usable q → fall back to the path coordinate.
        val pathPoint = pointFromPath(path) ?: return null
        return GeoUriTarget.Point(pathPoint, label = null)
    }

    /**
     * A `q=` value: either `lat,lng` (optionally `lat,lng(Label)`) → a
     * [GeoUriTarget.Point], or anything else non-blank → a
     * [GeoUriTarget.Query]. null/blank yields null.
     */
    private fun fromQueryValue(value: String?): GeoUriTarget? {
        val decoded = decode(value ?: return null).trim()
        if (decoded.isEmpty()) return null

        val labelStart = decoded.indexOf('(')
        val coordsPart = if (labelStart >= 0) decoded.substring(0, labelStart) else decoded
        val point = parseLatLng(coordsPart)
        if (point != null) {
            val label =
                if (labelStart >= 0) {
                    val labelEnd = decoded.lastIndexOf(')')
                    if (labelEnd > labelStart + 1) {
                        decoded.substring(labelStart + 1, labelEnd).trim().ifEmpty { null }
                    } else {
                        null
                    }
                } else {
                    null
                }
            return GeoUriTarget.Point(point, label)
        }
        // Not a coordinate → a free-text place query.
        return GeoUriTarget.Query(decoded)
    }

    /**
     * The path coordinate of a geo: URI, or null. `0,0` is treated as the "no
     * location" sentinel (not a real destination) and also yields null.
     */
    private fun pointFromPath(path: String): LatLng? {
        val point = parseLatLng(decode(path).trim()) ?: return null
        if (point.latitude == 0.0 && point.longitude == 0.0) return null
        return point
    }

    /**
     * `lat,lng` (a trailing `,altitude` or any further parts are ignored) as a
     * validated [LatLng], or null when it is not two in-range finite numbers.
     * Note the axis flip: geo: URIs are latitude-first, [LatLng] is
     * longitude-first.
     */
    private fun parseLatLng(text: String): LatLng? {
        // RFC 5870 allows ';'-prefixed parameters after the coordinates
        // (e.g. "lat,lng;u=35" for uncertainty radius, which some apps emit);
        // they are not part of the numeric pair, so drop them before parsing.
        val parts = text.substringBefore(';').split(',')
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lng = parts[1].trim().toDoubleOrNull() ?: return null
        val point = LatLng(longitude = lng, latitude = lat)
        return if (isValidWgs84Coordinate(point)) point else null
    }

    /** Splits an `a=1&b=2` query string into a map (last value wins on a repeat). */
    private fun paramsOf(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val key = pair.substring(0, eq).lowercase()
            result[key] = pair.substring(eq + 1)
        }
        return result
    }

    /**
     * URL-decodes a component, tolerating a value that was never encoded (a bad
     * `%` escape throws, in which case the raw text is the honest fallback).
     */
    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            value
        }
}
