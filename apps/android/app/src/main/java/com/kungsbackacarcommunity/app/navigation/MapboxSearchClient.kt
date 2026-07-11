package com.kungsbackacarcommunity.app.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data source for address autocomplete + driving directions.
 *
 * An interface so the [NavigationController] (and its tests) depend only on this
 * seam: production uses [HttpMapboxSearchClient]; tests inject a fake returning
 * canned results, keeping the controller's debounce/route logic unit-testable
 * without the network or a token.
 */
interface MapboxSearchClient {
    /** Autocomplete suggestions for [query], biased toward [proximity] if given. */
    suspend fun geocode(query: String, proximity: LatLng?): List<PlaceSuggestion>

    /** The driving route between two points, or null when none can be produced. */
    suspend fun route(origin: LatLng, destination: LatLng): RouteSummary?
}

/**
 * [MapboxSearchClient] backed by the Mapbox REST APIs (Geocoding v6 +
 * Directions v5) over [HttpURLConnection].
 *
 * ## Token guard (config-less CI / no network)
 * Constructed only with a non-blank public `pk.` token (the caller checks the
 * `mapbox_access_token` resource, mirroring [com.kungsbackacarcommunity.app.shell.rememberMapSurface]).
 * As a belt-and-braces guard every call also short-circuits to empty/null when
 * the token is blank, so no request is ever issued without a token — the
 * no-token build never touches the network.
 *
 * ## Offline-verification note
 * The request *shape* is built by the pure [MapboxRequests] (unit-tested); the
 * HTTP round-trip and the `org.json` response parsing here run only on device
 * and are therefore verified on device, not in JVM unit tests (org.json is not
 * on the unit-test classpath). Failures degrade to empty/null rather than
 * throwing, so a flaky network or an unexpected payload never crashes the UI.
 *
 * @param language optional BCP-47 language for localized results/instructions.
 */
class HttpMapboxSearchClient(
    private val token: String,
    private val language: String? = null,
) : MapboxSearchClient {
    override suspend fun geocode(query: String, proximity: LatLng?): List<PlaceSuggestion> {
        if (token.isBlank()) return emptyList()
        val url = MapboxRequests.forwardGeocode(query, token, proximity, language) ?: return emptyList()
        val body = runCatching { get(url) }.getOrNull() ?: return emptyList()
        return runCatching { parseSuggestions(body) }.getOrDefault(emptyList())
    }

    override suspend fun route(origin: LatLng, destination: LatLng): RouteSummary? {
        if (token.isBlank()) return null
        val url = MapboxRequests.directions(origin, destination, token, language)
        val body = runCatching { get(url) }.getOrNull() ?: return null
        return runCatching { parseRoute(body) }.getOrNull()
    }

    private suspend fun get(url: String): String? =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000

        /** Maps a Mapbox Geocoding v6 FeatureCollection to [PlaceSuggestion]s. */
        fun parseSuggestions(json: String): List<PlaceSuggestion> {
            val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<PlaceSuggestion>(features.length())
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val props = feature.optJSONObject("properties") ?: continue
                val coordinates =
                    feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (coordinates.length() < 2) continue
                val name = props.optString("name").ifBlank { props.optString("full_address") }
                if (name.isBlank()) continue
                val fullAddress =
                    props.optString("full_address").ifBlank { props.optString("place_formatted") }
                out.add(
                    PlaceSuggestion(
                        id = props.optString("mapbox_id").ifBlank { "$i-$name" },
                        name = name,
                        address = fullAddress.takeIf { it.isNotBlank() && it != name },
                        point =
                            LatLng(
                                longitude = coordinates.optDouble(0),
                                latitude = coordinates.optDouble(1),
                            ),
                    ),
                )
            }
            return out
        }

        /** Maps a Mapbox Directions v5 response's first route to [RouteSummary]. */
        fun parseRoute(json: String): RouteSummary? {
            val route = JSONObject(json).optJSONArray("routes")?.optJSONObject(0) ?: return null
            val geometry = PolylineCodec.decode(route.optString("geometry"))
            val steps = ArrayList<RouteStep>()
            val legs = route.optJSONArray("legs")
            if (legs != null) {
                for (l in 0 until legs.length()) {
                    val legSteps = legs.optJSONObject(l)?.optJSONArray("steps") ?: continue
                    for (s in 0 until legSteps.length()) {
                        val step = legSteps.optJSONObject(s) ?: continue
                        val instruction = step.optJSONObject("maneuver")?.optString("instruction").orEmpty()
                        if (instruction.isBlank()) continue
                        steps.add(
                            RouteStep(
                                instruction = instruction,
                                distanceMeters = step.optDouble("distance", 0.0),
                            ),
                        )
                    }
                }
            }
            return RouteSummary(
                distanceMeters = route.optDouble("distance", 0.0),
                durationSeconds = route.optDouble("duration", 0.0),
                geometry = geometry,
                steps = steps,
            )
        }
    }
}
