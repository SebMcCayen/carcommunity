package com.kungsbackacarcommunity.app.navigation

import com.kungsbackacarcommunity.app.diagnostics.CrashFeatures
import com.kungsbackacarcommunity.app.diagnostics.CrashTelemetry
import com.kungsbackacarcommunity.app.diagnostics.NoopCrashTelemetry
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
    /** Previously selected places, shown in the empty (pre-typing) search state. */
    val recents: List<PlaceSuggestion> = emptyList(),
    /**
     * The user's saved places (Home/Work/favourites), shown above [recents] in
     * the empty search state for one-tap routing.
     */
    val savedPlaces: List<SavedPlace> = emptyList(),
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
 * @param recentStore persistence for the user's most-recently selected places;
 *   seeded into the initial state and updated when a destination is picked.
 * @param savedStore persistence for the user's saved places (Home/Work/
 *   favourites); seeded into the initial state and re-read after every edit.
 *   Local and synchronous, so saving never blocks or needs the network.
 * @param crashTelemetry sink for the swallowed origin-resolution failure below.
 *   Defaults to the no-op so unit tests need no Firebase.
 */
class NavigationController(
    private val client: MapboxSearchClient,
    private val originProvider: suspend () -> LatLng?,
    private val scope: CoroutineScope,
    private val recentStore: RecentSearchesStore = InMemoryRecentSearchesStore(),
    private val savedStore: SavedPlacesStore = InMemorySavedPlacesStore(),
    private val crashTelemetry: CrashTelemetry = NoopCrashTelemetry,
) {
    private val stateFlow =
        MutableStateFlow(
            NavUiState(recents = recentStore.recent(), savedPlaces = savedStore.saved()),
        )
    val state: StateFlow<NavUiState> = stateFlow.asStateFlow()

    // Cached once per screen open; used for proximity bias and route origin.
    private var cachedOrigin: LatLng? = null

    private var searchJob: Job? = null
    private var routeJob: Job? = null

    // Tracks the reverse-geocode launched by selectPoint(); held so a newer
    // action (typing, another pick, another long-press, or a clear) can cancel it
    // and its stale result never overwrites the newer UI state / route job.
    private var pointJob: Job? = null

    /** Fetches the current location up-front so autocomplete can bias by it. */
    fun refreshOrigin() {
        scope.launch { cachedOrigin = runCatchingCancellable { originProvider() }.getOrNull() }
    }

    /**
     * Handles a query edit. Clears results immediately on an empty field;
     * otherwise (re)starts a debounced geocoding lookup, cancelling any prior
     * one so only the latest keystroke hits the network.
     */
    fun onQueryChange(query: String) {
        searchJob?.cancel()
        // A fresh query supersedes any pending long-press reverse-geocode, so its
        // late result can't call select() and clobber the search that's starting.
        pointJob?.cancel()
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
                    // Nearest-first. `cachedOrigin` already biases the request, but
                    // that only tilts the API's relevance ranking — it can still put
                    // a better-matching further place first. Ordering the matches by
                    // actual distance is what makes the top result the one you'd
                    // drive to. Without a fix this is a no-op (see NavGeo).
                    stateFlow.value =
                        stateFlow.value.copy(
                            suggestions = NavGeo.nearestFirst(results, cachedOrigin),
                            searching = false,
                        )
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
        // Cancel any pending long-press reverse-geocode so it can't re-select a
        // stale point over this pick. When select() is itself invoked from that
        // reverse-geocode's coroutine (selectPoint below), this self-cancel is
        // harmless: select() has no suspension points before it returns, so it
        // runs to completion and the route job it starts lives on the outer scope.
        pointJob?.cancel()
        // Persist the pick as a recent so it reappears in the empty search state
        // next time; re-read so the promoted+capped list drives the UI too.
        recentStore.record(suggestion)
        stateFlow.value =
            stateFlow.value.copy(
                query = suggestion.name,
                destination = suggestion,
                suggestions = emptyList(),
                searching = false,
                route = null,
                routeLoading = true,
                error = null,
                recents = recentStore.recent(),
            )
        routeJob =
            scope.launch {
                val origin =
                    cachedOrigin
                        ?: try {
                            originProvider()?.also { cachedOrigin = it }
                        } catch (e: CancellationException) {
                            throw e // never swallow cancellation while fetching the origin
                        } catch (failure: Exception) {
                            // Real failure resolving the origin → NoOrigin below.
                            // The user just sees "we don't know where you are",
                            // which reads identically to a denied permission, so
                            // a genuine location-provider fault is otherwise
                            // indistinguishable from normal. Record it.
                            crashTelemetry.recordNonFatal(CrashFeatures.NAV_ORIGIN, failure)
                            null
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
     * Picks a raw map coordinate (a long-press "navigate here") as the
     * destination: reverse-geocodes it to a place name/address for the preview
     * label, then routes to it exactly like a selected suggestion. Falls back to
     * [fallbackLabel] as the name and the raw lat/lng as the address when reverse
     * geocoding returns nothing (or has no token). The destination stays the
     * pressed [point] so navigation goes to the tapped spot, not a snapped one.
     *
     * @param fallbackLabel localized "Dropped pin" label supplied by the UI (the
     *   controller holds no Android resources).
     */
    fun selectPoint(point: LatLng, fallbackLabel: String, knownName: String? = null) {
        searchJob?.cancel()
        routeJob?.cancel()
        // Supersede any in-flight long-press resolution from a previous press.
        pointJob?.cancel()
        // Show the destination immediately (loading) so the sheet appears the
        // instant the user lifts their finger, before reverse geocoding resolves.
        val pending =
            PlaceSuggestion(
                id = pinId(point),
                name = knownName ?: fallbackLabel,
                address = rawCoordinates(point),
                point = point,
            )
        stateFlow.value =
            stateFlow.value.copy(
                query = pending.name,
                destination = pending,
                suggestions = emptyList(),
                searching = false,
                route = null,
                routeLoading = true,
                error = null,
            )
        pointJob =
            scope.launch {
                val resolved =
                    runCatchingCancellable { client.reverseGeocode(point) }.getOrNull()
                // reverseGeocode may block non-cooperatively; if a newer action
                // cancelled this job while it was in flight, bail before touching
                // state so a stale point never re-selects over the newer one.
                coroutineContext.ensureActive()
                // Keep the pressed point as the destination; use the resolved
                // name/address for the label when available, else the pending fallback.
                //
                // A KNOWN name wins over the resolved one: a tapped place already
                // carries the basemap's own label for it, and reverse geocoding a
                // shop's coordinate answers with its street address — a worse
                // answer to "where am I going?" than the name the user just tapped.
                // Its address is still worth taking, so only the name is pinned.
                val suggestion =
                    if (resolved != null) {
                        pending.copy(
                            name = knownName ?: resolved.name,
                            address = resolved.address ?: pending.address,
                        )
                    } else {
                        pending
                    }
                // Route + record-as-recent go through the same select() path.
                select(suggestion)
            }
    }

    /**
     * Saves [place] as a [kind] shortcut under [label], or updates it when it is
     * already saved (see [SavedPlaces.upsert]: a new Home replaces the old one,
     * a re-saved favourite is refreshed in place rather than duplicated). This is
     * also the **rename** path — re-saving an entry with a new [label] rewrites
     * the same id — so there is one write path rather than two that can diverge.
     *
     * Synchronous — the store is a local key-value write — so the UI reflects the
     * save on the same frame the user taps, with no spinner and no network.
     */
    fun savePlace(
        place: PlaceSuggestion,
        kind: SavedPlaceKind,
        label: String,
    ) {
        val saved = SavedPlaces.create(kind, place, label)
        // Drop every OTHER row pointing at this place before writing the new one.
        //
        // Re-saving under a different kind changes the id (a promoted favourite
        // becomes "home"), and upsert replaces by id — so without this the place
        // would stay saved twice. Sweeping ALL matches rather than just the first
        // also heals a store that already holds duplicates of one place, which
        // normalize() cannot collapse for us: it de-duplicates by id, and a Home
        // and a Favourite for the same place have different ids.
        //
        // Entries sharing the new id are deliberately left for upsert: that is the
        // rename/refresh case, and upsert replaces them **in place**, preserving
        // the slot (and so the cap-eviction age) that a remove-then-add would lose.
        SavedPlaces
            .matching(savedStore.saved(), place)
            .filter { it.id != saved.id }
            .forEach { savedStore.remove(it.id) }
        savedStore.save(saved)
        refreshSaved()
    }

    /** Un-saves the place [id]. The destination/route in view is left untouched. */
    fun removeSavedPlace(id: String) {
        savedStore.remove(id)
        refreshSaved()
    }

    /** Re-reads the store so the ordered/capped list drives the UI. */
    private fun refreshSaved() {
        stateFlow.value = stateFlow.value.copy(savedPlaces = savedStore.saved())
    }

    /**
     * Clears the picked destination + route, returning to the search field. The
     * host observes [state] and wipes the map overlay when the route is gone.
     */
    fun clearDestination() {
        searchJob?.cancel()
        routeJob?.cancel()
        // Drop any pending long-press resolution so it can't re-open a route after
        // the user cleared the destination.
        pointJob?.cancel()
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

        /** Stable id for a dropped-pin place (so recents de-dupe by coordinate). */
        fun pinId(point: LatLng): String =
            "pin:${fmt(point.longitude)},${fmt(point.latitude)}"

        /** Raw "lat, lng" address fallback when reverse geocoding yields nothing. */
        fun rawCoordinates(point: LatLng): String =
            "${fmt(point.latitude)}, ${fmt(point.longitude)}"

        private fun fmt(value: Double): String =
            String.format(java.util.Locale.US, "%.5f", value)
    }
}
