package com.kungsbackacarcommunity.app.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationControllerTest {
    private val origin = LatLng(longitude = 12.0757, latitude = 57.4874)
    private val suggestion =
        PlaceSuggestion(
            id = "1",
            name = "Kungsbacka torg",
            address = "434 30 Kungsbacka",
            point = LatLng(longitude = 12.08, latitude = 57.49),
        )
    private val routeSummary =
        RouteSummary(
            distanceMeters = 4523.0,
            durationSeconds = 720.0,
            geometry = listOf(origin, suggestion.point),
            steps = listOf(RouteStep("Head north", 200.0)),
        )

    private class FakeClient(
        private val suggestions: List<PlaceSuggestion> = emptyList(),
        private val route: RouteSummary? = null,
        private val reverse: PlaceSuggestion? = null,
    ) : MapboxSearchClient {
        var lastQuery: String? = null
        var lastProximity: LatLng? = null
        var geocodeCalls: Int = 0
        var lastReversePoint: LatLng? = null

        override suspend fun geocode(query: String, proximity: LatLng?): List<PlaceSuggestion> {
            geocodeCalls++
            lastQuery = query
            lastProximity = proximity
            return suggestions
        }

        override suspend fun route(origin: LatLng, destination: LatLng): RouteSummary? = route

        override suspend fun reverseGeocode(point: LatLng): PlaceSuggestion? {
            lastReversePoint = point
            return reverse
        }
    }

    @Test
    fun `query change debounces then emits suggestions`() = runTest {
        val client = FakeClient(suggestions = listOf(suggestion))
        val controller = NavigationController(client, originProvider = { origin }, scope = this)

        controller.onQueryChange("kung")
        advanceTimeBy(200)
        runCurrent()
        // Still within the debounce window — no lookup yet.
        assertTrue(controller.state.value.suggestions.isEmpty())
        assertEquals(0, client.geocodeCalls)

        advanceUntilIdle()
        assertEquals(listOf(suggestion), controller.state.value.suggestions)
        assertFalse(controller.state.value.searching)
        assertEquals(1, client.geocodeCalls)
    }

    @Test
    fun `rapid typing only runs the latest lookup`() = runTest {
        val client = FakeClient(suggestions = listOf(suggestion))
        val controller = NavigationController(client, originProvider = { origin }, scope = this)

        controller.onQueryChange("k")
        advanceTimeBy(100)
        controller.onQueryChange("ku")
        advanceTimeBy(100)
        controller.onQueryChange("kun")
        advanceUntilIdle()

        assertEquals(1, client.geocodeCalls)
        assertEquals("kun", client.lastQuery)
    }

    @Test
    fun `a new keystroke clears the spinner during the next debounce window`() = runTest {
        // Gate the first lookup so it stays in-flight with `searching = true`.
        val gate = CompletableDeferred<Unit>()
        val client =
            object : MapboxSearchClient {
                override suspend fun geocode(query: String, proximity: LatLng?): List<PlaceSuggestion> {
                    gate.await()
                    return listOf(suggestion)
                }

                override suspend fun route(origin: LatLng, destination: LatLng): RouteSummary? = null
            }
        val controller = NavigationController(client, originProvider = { origin }, scope = this)

        controller.onQueryChange("kung")
        advanceTimeBy(300)
        runCurrent()
        // Lookup is now running and the spinner is up.
        assertTrue(controller.state.value.searching)

        // Next keystroke cancels the in-flight lookup; the spinner must clear
        // immediately rather than linger through the new debounce window.
        controller.onQueryChange("kungs")
        assertFalse(controller.state.value.searching)

        advanceTimeBy(299)
        runCurrent()
        // Still pre-lookup: no spinner while merely debouncing.
        assertFalse(controller.state.value.searching)

        // Let the latest (gated) lookup finish so no coroutine is left dangling.
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(controller.state.value.searching)
    }

    @Test
    fun `blank query clears suggestions without a lookup`() = runTest {
        val client = FakeClient(suggestions = listOf(suggestion))
        val controller = NavigationController(client, originProvider = { origin }, scope = this)
        controller.onQueryChange("kung")
        advanceUntilIdle()
        assertTrue(controller.state.value.suggestions.isNotEmpty())

        controller.onQueryChange("")
        advanceUntilIdle()
        assertTrue(controller.state.value.suggestions.isEmpty())
    }

    @Test
    fun `refreshed origin biases the geocode proximity`() = runTest {
        val client = FakeClient(suggestions = listOf(suggestion))
        val controller = NavigationController(client, originProvider = { origin }, scope = this)
        controller.refreshOrigin()
        advanceUntilIdle()

        controller.onQueryChange("torg")
        advanceUntilIdle()
        assertEquals(origin, client.lastProximity)
    }

    @Test
    fun `a failing origin provider leaves the geocode proximity unbiased`() = runTest {
        // A real (non-cancellation) failure resolving the origin must still
        // degrade to null via runCatchingCancellable's failure branch, exactly
        // as the old swallowing runCatching did — so autocomplete is simply
        // unbiased rather than crashing.
        val client = FakeClient(suggestions = listOf(suggestion))
        val controller =
            NavigationController(
                client,
                originProvider = { throw RuntimeException("no fix") },
                scope = this,
            )
        controller.refreshOrigin()
        advanceUntilIdle()

        controller.onQueryChange("torg")
        advanceUntilIdle()
        assertEquals(listOf(suggestion), controller.state.value.suggestions)
        assertNull(client.lastProximity)
    }

    @Test
    fun `selecting a suggestion fetches the route`() = runTest {
        val client = FakeClient(route = routeSummary)
        val controller = NavigationController(client, originProvider = { origin }, scope = this)
        controller.refreshOrigin()
        advanceUntilIdle()

        controller.select(suggestion)
        assertTrue(controller.state.value.routeLoading)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(suggestion, state.destination)
        assertEquals(routeSummary, state.route)
        assertFalse(state.routeLoading)
        assertNull(state.error)
    }

    @Test
    fun `selectPoint reverse-geocodes the label and routes to the pressed point`() = runTest {
        val pressed = LatLng(longitude = 12.10, latitude = 57.50)
        val resolved =
            PlaceSuggestion(id = "poi", name = "Kungsmässan", address = "Innerstaden", point = pressed)
        val client = FakeClient(route = routeSummary, reverse = resolved)
        val controller = NavigationController(client, originProvider = { origin }, scope = this)
        controller.refreshOrigin()
        advanceUntilIdle()

        controller.selectPoint(pressed, fallbackLabel = "Dropped pin")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(pressed, client.lastReversePoint)
        // Resolved name is used for the label; the destination stays the pressed point.
        assertEquals("Kungsmässan", state.destination?.name)
        assertEquals(pressed, state.destination?.point)
        assertEquals(routeSummary, state.route)
        // The dropped pin is recorded as a recent for one-tap re-selection.
        assertEquals("Kungsmässan", controller.state.value.recents.firstOrNull()?.name)
    }

    @Test
    fun `selectPoint falls back to the dropped-pin label when reverse geocoding is empty`() = runTest {
        val pressed = LatLng(longitude = 12.10, latitude = 57.50)
        val client = FakeClient(route = routeSummary, reverse = null)
        val controller = NavigationController(client, originProvider = { origin }, scope = this)
        controller.refreshOrigin()
        advanceUntilIdle()

        controller.selectPoint(pressed, fallbackLabel = "Dropped pin")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals("Dropped pin", state.destination?.name)
        assertEquals(pressed, state.destination?.point)
        // Address falls back to the raw coordinates.
        assertTrue(state.destination?.address?.contains("57.50000") == true)
    }

    @Test
    fun `selecting with no origin surfaces the no-origin error`() = runTest {
        val client = FakeClient(route = routeSummary)
        val controller = NavigationController(client, originProvider = { null }, scope = this)

        controller.select(suggestion)
        advanceUntilIdle()

        assertEquals(NavError.NoOrigin, controller.state.value.error)
        assertNull(controller.state.value.route)
    }

    @Test
    fun `a null route surfaces the route error`() = runTest {
        val client = FakeClient(route = null)
        val controller = NavigationController(client, originProvider = { origin }, scope = this)
        controller.refreshOrigin()
        advanceUntilIdle()

        controller.select(suggestion)
        advanceUntilIdle()

        assertEquals(NavError.Route, controller.state.value.error)
    }

    @Test
    fun `clearing the destination returns to search`() = runTest {
        val client = FakeClient(route = routeSummary)
        val controller = NavigationController(client, originProvider = { origin }, scope = this)
        controller.refreshOrigin()
        advanceUntilIdle()
        controller.select(suggestion)
        advanceUntilIdle()

        controller.clearDestination()
        val state = controller.state.value
        assertNull(state.destination)
        assertNull(state.route)
        assertEquals("", state.query)
    }

    @Test
    fun `clearing cancels an in-flight lookup and no stale suggestions reappear`() = runTest {
        // Gate the geocode so the lookup stays in-flight while we clear.
        val gate = CompletableDeferred<Unit>()
        val client =
            object : MapboxSearchClient {
                override suspend fun geocode(query: String, proximity: LatLng?): List<PlaceSuggestion> {
                    gate.await()
                    return listOf(suggestion)
                }

                override suspend fun route(origin: LatLng, destination: LatLng): RouteSummary? = null
            }
        val controller = NavigationController(client, originProvider = { origin }, scope = this)

        controller.onQueryChange("kung")
        advanceTimeBy(300)
        runCurrent()
        // Lookup is running and the spinner is up.
        assertTrue(controller.state.value.searching)

        // Clearing must cancel the in-flight geocode and wipe search state.
        controller.clearDestination()
        var state = controller.state.value
        assertFalse(state.searching)
        assertTrue(state.suggestions.isEmpty())
        assertEquals("", state.query)

        // Even if the cancelled geocode's body were to complete, its result must
        // not leak back into state as stale suggestions.
        gate.complete(Unit)
        advanceUntilIdle()
        state = controller.state.value
        assertFalse(state.searching)
        assertTrue(state.suggestions.isEmpty())
    }

    @Test
    fun `a cancelled geocode that still returns does not write stale suggestions`() = runTest {
        // Model a non-cooperative blocking network call (HttpURLConnection):
        // cancellation can't interrupt it, so the geocode still returns a result
        // after the job was cancelled. The controller's `ensureActive()` guard
        // must stop that result from leaking into state.
        val gate = CompletableDeferred<Unit>()
        val client =
            object : MapboxSearchClient {
                override suspend fun geocode(query: String, proximity: LatLng?): List<PlaceSuggestion> {
                    withContext(NonCancellable) { gate.await() }
                    return listOf(suggestion)
                }

                override suspend fun route(origin: LatLng, destination: LatLng): RouteSummary? = null
            }
        val controller = NavigationController(client, originProvider = { origin }, scope = this)

        controller.onQueryChange("kung")
        advanceTimeBy(300)
        runCurrent()
        assertTrue(controller.state.value.searching)

        // Cancel the in-flight lookup, then let the non-cooperative call complete.
        controller.clearDestination()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(controller.state.value.suggestions.isEmpty())
        assertFalse(controller.state.value.searching)
    }

    @Test
    fun `a cancelled route that still returns does not write a stale route`() = runTest {
        // Same non-cooperative model for the directions call: a route that
        // returns after the job was cancelled must not overwrite cleared state.
        val gate = CompletableDeferred<Unit>()
        val client =
            object : MapboxSearchClient {
                override suspend fun geocode(query: String, proximity: LatLng?): List<PlaceSuggestion> = emptyList()

                override suspend fun route(origin: LatLng, destination: LatLng): RouteSummary? {
                    withContext(NonCancellable) { gate.await() }
                    return routeSummary
                }
            }
        val controller = NavigationController(client, originProvider = { origin }, scope = this)
        controller.refreshOrigin()
        advanceUntilIdle()

        controller.select(suggestion)
        assertTrue(controller.state.value.routeLoading)

        // Cancel the in-flight route, then let the non-cooperative call complete.
        controller.clearDestination()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(controller.state.value.route)
        assertFalse(controller.state.value.routeLoading)
    }

    @Test
    fun `search still works after clearing the destination`() = runTest {
        val client = FakeClient(suggestions = listOf(suggestion), route = routeSummary)
        val controller = NavigationController(client, originProvider = { origin }, scope = this)
        controller.refreshOrigin()
        advanceUntilIdle()
        controller.select(suggestion)
        advanceUntilIdle()

        controller.clearDestination()
        assertTrue(controller.state.value.suggestions.isEmpty())

        // A fresh query re-creates the search job and emits suggestions again.
        controller.onQueryChange("torg")
        advanceUntilIdle()
        assertEquals(listOf(suggestion), controller.state.value.suggestions)
    }

    @Test
    fun `saved places are seeded from the store into the initial state`() = runTest {
        val saved = SavedPlaces.create(SavedPlaceKind.Home, suggestion, "h")
        val controller =
            NavigationController(
                FakeClient(),
                originProvider = { origin },
                scope = this,
                savedStore = InMemorySavedPlacesStore(listOf(saved)),
            )
        assertEquals(listOf(saved), controller.state.value.savedPlaces)
    }

    @Test
    fun `savePlace persists and surfaces the place in state`() = runTest {
        val store = InMemorySavedPlacesStore()
        val controller =
            NavigationController(
                FakeClient(),
                originProvider = { origin },
                scope = this,
                savedStore = store,
            )
        controller.savePlace(suggestion, SavedPlaceKind.Favourite, "Torget")

        assertEquals(listOf("Torget"), controller.state.value.savedPlaces.map { it.label })
        // Written through to the store, not merely held in state.
        assertEquals(listOf("Torget"), store.saved().map { it.label })
    }

    @Test
    fun `re-saving with a new label renames in place`() = runTest {
        val controller =
            NavigationController(FakeClient(), originProvider = { origin }, scope = this)
        controller.savePlace(suggestion, SavedPlaceKind.Favourite, "Old")
        controller.savePlace(suggestion, SavedPlaceKind.Favourite, "New")

        assertEquals(listOf("New"), controller.state.value.savedPlaces.map { it.label })
    }

    @Test
    fun `re-saving under a different kind moves the place instead of duplicating it`() = runTest {
        val controller =
            NavigationController(FakeClient(), originProvider = { origin }, scope = this)
        controller.savePlace(suggestion, SavedPlaceKind.Favourite, "Torget")
        // Promoting the favourite to Home changes its id, so the old favourite row
        // must be dropped rather than left behind alongside the new Home.
        controller.savePlace(suggestion, SavedPlaceKind.Home, "Torget")

        val saved = controller.state.value.savedPlaces
        assertEquals(1, saved.size)
        assertEquals(SavedPlaceKind.Home, saved.single().kind)
    }

    @Test
    fun `removeSavedPlace drops it from state and leaves the route untouched`() = runTest {
        val controller =
            NavigationController(
                FakeClient(suggestions = listOf(suggestion), route = routeSummary),
                originProvider = { origin },
                scope = this,
            )
        controller.select(suggestion)
        advanceUntilIdle()
        controller.savePlace(suggestion, SavedPlaceKind.Favourite, "Torget")
        val id = controller.state.value.savedPlaces.single().id

        controller.removeSavedPlace(id)

        assertTrue(controller.state.value.savedPlaces.isEmpty())
        // Un-saving is not a navigation action: the previewed route stays put.
        assertEquals(suggestion, controller.state.value.destination)
        assertEquals(routeSummary, controller.state.value.route)
    }
}
