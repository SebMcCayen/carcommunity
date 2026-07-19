package com.kungsbackacarcommunity.app.navigation

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

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
 * Store of the user's most-recently selected places, surfaced in the search
 * bar's empty state so a place can be re-opened with a single tap.
 *
 * A seam (interface) so [NavigationController] and its tests depend only on this
 * — not on Android SharedPreferences: production uses the prefs-backed
 * implementation ([com.kungsbackacarcommunity.app.navigation.PrefsRecentSearchesStore]),
 * tests inject the in-memory [InMemoryRecentSearchesStore].
 */
interface RecentSearchesStore {
    /** Previously selected places, most-recent-first, already capped. */
    fun recent(): List<PlaceSuggestion>

    /** Records [place] as the newest recent (de-duplicated + capped). */
    fun record(place: PlaceSuggestion)
}

/**
 * Pure recent-search list logic (promote-to-front, de-duplicate, cap),
 * independent of any storage backend so it is JVM-unit-testable.
 */
object RecentSearches {
    /**
     * How many recents are persisted — and, deliberately, how many are shown:
     * the empty-search-state card renders them all, so stored == visible.
     *
     * There is no "show all" affordance, so any recent held beyond what the card
     * renders would persist, consume this cap, and never be reachable. Rather
     * than keep a second `SHOWN` constant that must agree with this one, the two
     * are one value.
     */
    const val MAX = 3

    /**
     * Returns [existing] with [place] promoted to the front: any prior entry for
     * the same place (matched by [PlaceSuggestion.id], else by coordinate) is
     * dropped first so a re-selected place moves up instead of duplicating, then
     * the list is capped to [max].
     */
    fun record(
        existing: List<PlaceSuggestion>,
        place: PlaceSuggestion,
        max: Int = MAX,
    ): List<PlaceSuggestion> {
        val deduped = existing.filterNot { it.samePlace(place) }
        return (listOf(place) + deduped).take(max)
    }

    private fun PlaceSuggestion.samePlace(other: PlaceSuggestion): Boolean =
        if (id.isNotBlank() && other.id.isNotBlank()) id == other.id else point == other.point
}

/**
 * In-memory [RecentSearchesStore] used by unit tests and Compose previews, and
 * as the default so a caller that has no persistence still works (recents just
 * do not survive process death).
 */
class InMemoryRecentSearchesStore(
    initial: List<PlaceSuggestion> = emptyList(),
) : RecentSearchesStore {
    private var items: List<PlaceSuggestion> = initial.take(RecentSearches.MAX)

    override fun recent(): List<PlaceSuggestion> = items

    override fun record(place: PlaceSuggestion) {
        items = RecentSearches.record(items, place)
    }
}

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
 * Great-circle geometry + result ordering for search. Pure, so the ordering
 * rule is unit-tested without the network or a device.
 */
object NavGeo {
    /** Mean Earth radius (m) — the standard spherical approximation. */
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Great-circle (haversine) distance in metres between two coordinates.
     * Spherical, not ellipsoidal: at the scale that decides "which of these
     * search results is nearest" the difference is far below the precision of
     * the fix itself, and it never has to be exact — only correctly ORDERED.
     */
    fun distanceMeters(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val a =
            sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    /**
     * Search results ordered nearest-first from [origin].
     *
     * The API is already given a proximity bias, but that only BIASES relevance
     * ranking — it is free to put a better-matching further place above a nearer
     * one, so "Statoil" could list a branch three towns away above the one down
     * the road. For a driving app the ordering people actually want from a list
     * of matches is "which of these is closest to me", so the bias picks the
     * candidates and this decides their order.
     *
     * A null [origin] (permission denied, or no fix yet) keeps the API's own
     * relevance order untouched — with no location, "nearest" has no meaning, and
     * an arbitrary re-shuffle would be strictly worse than what the API returned.
     *
     * Stable: equidistant results keep their relative API order.
     */
    fun nearestFirst(results: List<PlaceSuggestion>, origin: LatLng?): List<PlaceSuggestion> {
        if (origin == null) return results
        return results.sortedBy { distanceMeters(origin, it.point) }
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
     * Default country bias (ISO 3166-1 alpha-2). The app is a Kungsbacka/Sweden
     * community app, so results are constrained to Sweden — this both localizes
     * the ranking and keeps the small result set relevant.
     */
    const val DEFAULT_COUNTRY = "SE"

    /**
     * Fallback proximity bias (Kungsbacka town centroid, lng/lat) used when the
     * caller has no live location — GPS permission denied, no fix yet, or the
     * lookup raced ahead of [NavigationController.refreshOrigin]. Search Box
     * ranks results near `proximity` first, so without any bias a query like
     * "Kungsmässan" could surface a same-named place elsewhere before the local
     * mall. Biasing to the app's home region keeps *nearby* results first even
     * before a fix arrives; a real user fix (when present) always takes priority
     * over this constant. Sweden-wide relevance is still enforced by [country].
     */
    private val DEFAULT_PROXIMITY = LatLng(longitude = 12.0730, latitude = 57.4874)

    /**
     * Forward search request against the Mapbox **Search Box** API's `/forward`
     * endpoint.
     *
     * Unlike Geocoding v6 (the previous backend), the Search Box API resolves
     * points of interest / businesses ("Kungsmässan", a café, a workshop) in
     * addition to addresses and streets — a single GET returns both, as a GeoJSON
     * FeatureCollection whose features carry `properties.name` / `full_address` /
     * `mapbox_id` and a `geometry.coordinates` pair, so the existing parser is
     * unchanged. `/forward` (not `/suggest` + `/retrieve`) is used deliberately:
     * it needs no interactive session token yet still returns coordinates inline.
     *
     * Results are biased to the app's locale/region: [proximity] toward the user
     * when known (falling back to [DEFAULT_PROXIMITY], the Kungsbacka centroid,
     * when no fix is available so *nearby* results still rank first), [country]
     * (Sweden by default), and [language] for localized labels. `types` is left
     * unset deliberately: the Search Box `/forward` endpoint then returns every
     * feature type — POIs/businesses (e.g. "Kungsmässan") alongside addresses,
     * streets and places — rather than an address-only subset. Returns null for a
     * blank query so callers never issue an empty request.
     */
    fun forwardGeocode(
        query: String,
        token: String,
        proximity: LatLng? = null,
        language: String? = null,
        limit: Int = GEOCODE_LIMIT,
        country: String? = DEFAULT_COUNTRY,
    ): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        // Real user location first; else bias to the app's home region so nearby
        // POIs still outrank same-named places elsewhere in Sweden.
        val bias = proximity ?: DEFAULT_PROXIMITY
        val sb =
            StringBuilder("https://api.mapbox.com/search/searchbox/v1/forward")
                .append("?q=").append(encode(trimmed))
                .append("&limit=").append(limit)
                .append("&access_token=").append(encode(token))
        sb.append("&proximity=")
            .append(fmt(bias.longitude)).append(",").append(fmt(bias.latitude))
        if (!country.isNullOrBlank()) {
            sb.append("&country=").append(encode(country))
        }
        if (!language.isNullOrBlank()) {
            sb.append("&language=").append(encode(language))
        }
        return sb.toString()
    }

    /**
     * Reverse-geocoding request against the Mapbox **Search Box** API's
     * `/reverse` endpoint: resolves a coordinate (e.g. a map long-press) to the
     * nearest place/address as a GeoJSON FeatureCollection in the same shape the
     * forward parser reads. [limit] defaults to 1 (only the nearest match is
     * needed to label a dropped pin).
     */
    fun reverseGeocode(
        point: LatLng,
        token: String,
        language: String? = null,
        limit: Int = 1,
    ): String {
        val sb =
            StringBuilder("https://api.mapbox.com/search/searchbox/v1/reverse")
                .append("?longitude=").append(fmt(point.longitude))
                .append("&latitude=").append(fmt(point.latitude))
                .append("&limit=").append(limit)
                .append("&access_token=").append(encode(token))
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
