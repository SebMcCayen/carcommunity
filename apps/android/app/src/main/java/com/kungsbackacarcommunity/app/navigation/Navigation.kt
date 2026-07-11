package com.kungsbackacarcommunity.app.navigation

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Pure (Android-free, Mapbox-free) core for the address-search + directions
 * feature that backs the map-home "Where to?" bar.
 *
 * Everything here is JVM-unit-testable: the domain models, the human-readable
 * distance/ETA formatting, the polyline-6 geometry decoder, and the Mapbox REST
 * request builders. The on-device glue (HTTP, JSON parsing, Mapbox rendering,
 * GPS) lives in the sibling Android files so this stays deterministic and
 * exercised in CI without a token, a device, or the network.
 */

/** A longitude/latitude pair (Mapbox lng-first ordering). */
data class LatLng(
    val longitude: Double,
    val latitude: Double,
)

/**
 * A geocoding result the user can pick as a destination. [point] is the
 * resolved coordinate (Mapbox geocoding v6 returns it inline). [name] is the
 * primary label (e.g. a street or place name); [address] is the fuller
 * formatted address shown underneath, when present.
 */
data class PlaceSuggestion(
    val id: String,
    val name: String,
    val address: String?,
    val point: LatLng,
)

/** A single turn-by-turn maneuver in a route. */
data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
)

/**
 * A driving route from the user's location to the chosen destination: total
 * distance + duration, the ordered geometry to draw as a line, and the
 * step-by-step maneuver list.
 */
data class RouteSummary(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: List<LatLng>,
    val steps: List<RouteStep>,
)

/** Why a search or route request could not be fulfilled (drives an inline hint). */
enum class NavError {
    /** The geocoding request failed (network/HTTP/parse). */
    Search,

    /** The directions request failed (network/HTTP/parse), or no route exists. */
    Route,

    /** No usable origin — location permission missing or no fix yet. */
    NoOrigin,
}

/**
 * Locale-aware distance/ETA formatting. Unit labels are passed in (sourced from
 * string resources) so the numeric rounding stays pure and testable while the
 * abbreviations remain localizable.
 */
object NavFormat {
    /**
     * Formats a distance: metres (rounded to the nearest 10) below 1 km,
     * otherwise kilometres with one decimal. Negative inputs are clamped to 0.
     */
    fun formatDistance(
        meters: Double,
        metersLabel: String,
        kilometersLabel: String,
    ): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m < 1000.0) {
            val rounded = (m / 10.0).roundToLong() * 10
            "$rounded $metersLabel"
        } else {
            val km = m / 1000.0
            "${String.format(Locale.getDefault(), "%.1f", km)} $kilometersLabel"
        }
    }

    /**
     * Formats a duration: whole minutes below an hour, otherwise
     * "H h M min" (the minutes part is dropped when it rounds to 0). Negative
     * inputs are clamped to 0; anything under a minute shows as "1 min" so a
     * near destination never reads "0 min".
     */
    fun formatDuration(
        seconds: Double,
        minutesLabel: String,
        hoursLabel: String,
    ): String {
        val totalMinutes = (seconds.coerceAtLeast(0.0) / 60.0).roundToInt().coerceAtLeast(1)
        if (totalMinutes < 60) return "$totalMinutes $minutesLabel"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0) {
            "$hours $hoursLabel"
        } else {
            "$hours $hoursLabel $minutes $minutesLabel"
        }
    }
}

/**
 * Decoder for Mapbox's encoded-polyline geometry. The Directions API is queried
 * with `geometries=polyline6` (1e6 precision), so this pure decoder turns the
 * compact string into the ordered [LatLng] list used to draw the route line —
 * no JSON coordinate arrays to parse, and fully unit-testable.
 */
object PolylineCodec {
    private const val PRECISION_6 = 1e6

    /** Decodes a polyline-6 string to lng/lat points. Returns empty on blank. */
    fun decode(encoded: String, precision: Double = PRECISION_6): List<LatLng> {
        if (encoded.isBlank()) return emptyList()
        val points = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        val len = encoded.length
        while (index < len) {
            var result = 1
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63 - 1
                result += b shl shift
                shift += 5
            } while (b >= 0x1f && index < len)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            result = 1
            shift = 0
            do {
                b = encoded[index++].code - 63 - 1
                result += b shl shift
                shift += 5
            } while (b >= 0x1f && index < len)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(LatLng(longitude = lng / precision, latitude = lat / precision))
        }
        return points
    }
}

/**
 * Builders for the Mapbox REST endpoints. Kept pure so the exact request shape
 * (endpoint, query params, token, coordinate ordering, URL-encoding) is
 * asserted in unit tests without ever hitting the network.
 */
object MapboxRequests {
    const val GEOCODE_LIMIT = 6

    /**
     * Forward-geocoding (autocomplete) request against Mapbox Geocoding v6.
     * [proximity] biases results toward the user when known. Returns null for a
     * blank query so callers never issue an empty request.
     */
    fun forwardGeocode(
        query: String,
        token: String,
        proximity: LatLng? = null,
        language: String? = null,
        limit: Int = GEOCODE_LIMIT,
    ): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        val sb =
            StringBuilder("https://api.mapbox.com/search/geocode/v6/forward")
                .append("?q=").append(encode(trimmed))
                .append("&autocomplete=true")
                .append("&limit=").append(limit)
                .append("&access_token=").append(encode(token))
        if (proximity != null) {
            sb.append("&proximity=")
                .append(fmt(proximity.longitude)).append(",").append(fmt(proximity.latitude))
        }
        if (!language.isNullOrBlank()) {
            sb.append("&language=").append(encode(language))
        }
        return sb.toString()
    }

    /**
     * Driving-directions request between two points. Asks for full overview
     * geometry as polyline-6 plus step maneuvers, so a single call yields both
     * the line to draw and the instruction list.
     */
    fun directions(
        origin: LatLng,
        destination: LatLng,
        token: String,
        language: String? = null,
    ): String {
        val coords =
            "${fmt(origin.longitude)},${fmt(origin.latitude)};" +
                "${fmt(destination.longitude)},${fmt(destination.latitude)}"
        val sb =
            StringBuilder("https://api.mapbox.com/directions/v5/mapbox/driving/")
                .append(coords)
                .append("?geometries=polyline6")
                .append("&overview=full")
                .append("&steps=true")
                .append("&access_token=").append(encode(token))
        if (!language.isNullOrBlank()) {
            sb.append("&language=").append(encode(language))
        }
        return sb.toString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Fixed 6-dp coordinate formatting, locale-independent (always a dot). */
    private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)
}
