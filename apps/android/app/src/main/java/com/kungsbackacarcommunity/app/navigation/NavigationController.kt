package com.kungsbackacarcommunity.app.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Immutable UI state for the address-search + directions flow.
 *
 * Three phases share one state:
 * - typing → [suggestions] (+ [searching] while a lookup is in flight),
 * - a picked [destination] with its [route] (+ [routeLoading]),
 * - an inline [error] hint (search/route/no-origin) that clears on the next input.
 */
data class NavUiState(
    val query: String = "",
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val searching: Boolean = false,
    val destination: PlaceSuggestion? = null,
    val route: RouteSummary? = null,
    val routeLoading: Boolean = false,
    val error: NavError? = null,
)

/**
 * Drives the "Where to?" search: debounced autocomplete, destination selection,
 * and driving-route retrieval. Holds no Android/Compose types — only coroutines
 * and the [MapboxSearchClient] seam — so the debounce, selection, and route
 * logic are unit-testable with a fake client and virtual time.
 *
 * The current-location [originProvider] supplies both the proximity bias for
 * autocomplete and the route origin; it returns null when no location is
 * available (permission denied / no fix), which surfaces as [NavError.NoOrigin]
 * when a route is requested.
 *
 * @param scope the coroutine scope the search/route jobs run in (the screen's).
 */
class NavigationController(
    private val client: MapboxSearchClient,
    private val originProvider: suspend () -> LatLng?,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = stateFlow.asStateFlow()

    // Cached once per screen open; used for proximity bias and route origin.
    private var cachedOrigin: LatLng? = null

    private var searchJob: Job? = null
    private var routeJob: Job? = null

    /** Fetches the current location up-front so autocomplete can bias by it. */
    fun refreshOrigin() {
        scope.launch { cachedOrigin = runCatching { originProvider() }.getOrNull() }
    }

    /**
     * Handles a query edit. Clears results immediately on an empty field;
     * otherwise (re)starts a debounced geocoding lookup, cancelling any prior
     * one so only the latest keystroke hits the network.
     */
    fun onQueryChange(query: String) {
        searchJob?.cancel()
        // Reset `searching` up front: cancelling the prior job doesn't clear the
        // spinner it may have set, so without this it would linger through the
        // pre-lookup debounce window even though no lookup is running. The
        // launched job below flips it true again once the actual geocode starts.
        stateFlow.value = stateFlow.value.copy(query = query, searching = false, error = null)
        if (query.isBlank()) {
            stateFlow.value = stateFlow.value.copy(suggestions = emptyList())
            return
        }
        searchJob =
            scope.launch {
                delay(DEBOUNCE_MS)
                stateFlow.value = stateFlow.value.copy(searching = true)
                try {
                    val results = client.geocode(query, cachedOrigin)
                    // The geocode call blocks in a non-cooperative network layer
                    // (HttpURLConnection), so a job cancelled mid-flight — e.g. the
                    // user kept typing — still returns here. Bail before writing so
                    // stale suggestions never overwrite the latest keystroke's state.
                    coroutineContext.ensureActive()
                    stateFlow.value =
                        stateFlow.value.copy(suggestions = results, searching = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Geocoding failed (network/HTTP/parse) — surface an inline
                    // hint and stop the spinner instead of hanging on `searching`.
                    stateFlow.value =
                        stateFlow.value.copy(searching = false, error = NavError.Search)
                }
            }
    }

    /**
     * Picks [suggestion] as the destination and fetches the driving route from
     * the current location. Missing origin → [NavError.NoOrigin]; a failed or
     * empty directions response → [NavError.Route].
     */
    fun select(suggestion: PlaceSuggestion) {
        searchJob?.cancel()
        routeJob?.cancel()
        stateFlow.value =
            stateFlow.value.copy(
                query = suggestion.name,
                destination = suggestion,
                suggestions = emptyList(),
                searching = false,
                route = null,
                routeLoading = true,
                error = null,
            )
        routeJob =
            scope.launch {
                val origin =
                    cachedOrigin
                        ?: try {
                            originProvider()?.also { cachedOrigin = it }
                        } catch (e: CancellationException) {
                            throw e // never swallow cancellation while fetching the origin
                        } catch (_: Exception) {
                            null // real failure resolving the origin → NoOrigin below
                        }
                // The origin fetch may block non-cooperatively; bail if the job was
                // cancelled (e.g. the user cleared the destination) before writing.
                coroutineContext.ensureActive()
                if (origin == null) {
                    stateFlow.value =
                        stateFlow.value.copy(routeLoading = false, error = NavError.NoOrigin)
                    return@launch
                }
                try {
                    val summary = client.route(origin, suggestion.point)
                    // The route call blocks in a non-cooperative network layer
                    // (HttpURLConnection), so a job cancelled mid-flight — e.g. the
                    // user picked a different destination or cleared — still returns
                    // here. Bail before writing so a stale route never lands in state.
                    coroutineContext.ensureActive()
                    if (summary == null) {
                        stateFlow.value =
                            stateFlow.value.copy(routeLoading = false, error = NavError.Route)
                    } else {
                        stateFlow.value =
                            stateFlow.value.copy(
                                route = summary,
                                routeLoading = false,
                                error = null,
                            )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Directions request failed — surface the route error and
                    // stop the spinner instead of hanging on `routeLoading`.
                    stateFlow.value =
                        stateFlow.value.copy(routeLoading = false, error = NavError.Route)
                }
            }
    }

    /**
     * Clears the picked destination + route, returning to the search field. The
     * host observes [state] and wipes the map overlay when the route is gone.
     */
    fun clearDestination() {
        searchJob?.cancel()
        routeJob?.cancel()
        stateFlow.value =
            stateFlow.value.copy(
                query = "",
                suggestions = emptyList(),
                searching = false,
                destination = null,
                route = null,
                routeLoading = false,
                error = null,
            )
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
