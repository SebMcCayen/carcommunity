package com.kungsbackacarcommunity.app.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    ) : MapboxSearchClient {
        var lastQuery: String? = null
        var lastProximity: LatLng? = null
        var geocodeCalls: Int = 0

        override suspend fun geocode(query: String, proximity: LatLng?): List<PlaceSuggestion> {
            geocodeCalls++
            lastQuery = query
            lastProximity = proximity
            return suggestions
        }

        override suspend fun route(origin: LatLng, destination: LatLng): RouteSummary? = route
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
}
