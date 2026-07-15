package com.kungsbackacarcommunity.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the address-search + directions overlay. Uses a fake
 * [MapboxSearchClient] + the [StubMapSurface] so no token, network, or GPS is
 * needed (mirrors the config-less/CI path).
 */
@RunWith(AndroidJUnit4::class)
class NavigationSearchScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val suggestion =
        PlaceSuggestion(
            id = "1",
            name = "Kungsbacka torg",
            address = "434 30 Kungsbacka",
            point = LatLng(longitude = 12.08, latitude = 57.49),
        )
    private val route =
        RouteSummary(
            distanceMeters = 4523.0,
            durationSeconds = 720.0,
            geometry = listOf(LatLng(12.0, 57.0), LatLng(12.08, 57.49)),
            steps = listOf(RouteStep("Head north on Main Street", 200.0)),
        )

    private class FakeClient(
        private val suggestions: List<PlaceSuggestion>,
        private val routeSummary: RouteSummary?,
    ) : MapboxSearchClient {
        override suspend fun geocode(query: String, proximity: LatLng?) = suggestions
        override suspend fun route(origin: LatLng, destination: LatLng) = routeSummary
    }

    @Test
    fun showsSearchPlaceholder() {
        composeTestRule.setContent {
            KccTheme {
                NavigationSearchScreen(
                    mapSurface = StubMapSurface(),
                    searchClient = FakeClient(emptyList(), null),
                    originProvider = { LatLng(12.0757, 57.4874) },
                    onClose = {},
                    onStartNavigation = { _, _ -> },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.addressSearch_searchPlaceholder))
            .assertIsDisplayed()
    }

    @Test
    fun typingShowsSuggestion_andSelectingShowsDirections() {
        composeTestRule.setContent {
            KccTheme {
                NavigationSearchScreen(
                    mapSurface = StubMapSurface(),
                    searchClient = FakeClient(listOf(suggestion), route),
                    originProvider = { LatLng(12.0757, 57.4874) },
                    onClose = {},
                    onStartNavigation = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithText(str(R.string.addressSearch_searchPlaceholder))
            .performTextInput("kung")

        // The lookup is debounced by a real-time delay() on the screen's
        // coroutine scope; a suspended delay does not keep Compose "busy", so
        // waitForIdle/assertIsDisplayed alone would race ahead of the debounce.
        // Poll until the debounced geocode has emitted the suggestion.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(suggestion.name).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(suggestion.name).assertIsDisplayed()

        composeTestRule.onNodeWithText(suggestion.name).performClick()

        // Route resolved → the directions list + first maneuver are shown.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(str(R.string.addressSearch_directionsTitle))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(str(R.string.addressSearch_directionsTitle))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Head north on Main Street").assertIsDisplayed()

        // The "Start" CTA appears once a route is resolved in EVERY build: the host
        // decides how it navigates (in-app Mapbox turn-by-turn when the SDK is
        // bundled, else a maps-app handoff), so the preview never dead-ends.
        composeTestRule.onNodeWithText(str(R.string.turnByTurn_start)).assertIsDisplayed()
    }

    @Test
    fun startButton_invokesOnStartNavigation_withDestination() {
        // The Start CTA is present in every build and always reports the pick via
        // onStartNavigation; the host then routes it to in-app turn-by-turn or a
        // maps-app handoff based on the build variant.
        var started: Pair<LatLng, String>? = null
        composeTestRule.setContent {
            KccTheme {
                NavigationSearchScreen(
                    mapSurface = StubMapSurface(),
                    searchClient = FakeClient(listOf(suggestion), route),
                    originProvider = { LatLng(12.0757, 57.4874) },
                    onClose = {},
                    onStartNavigation = { dest, label -> started = dest to label },
                )
            }
        }

        composeTestRule.onNodeWithText(str(R.string.addressSearch_searchPlaceholder))
            .performTextInput("kung")
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(suggestion.name).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(suggestion.name).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(str(R.string.turnByTurn_start))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithText(str(R.string.turnByTurn_start)).performClick()

        assertEquals(
            "Expected onStartNavigation with the picked destination, got $started",
            suggestion.point to suggestion.name,
            started,
        )
    }

    @Test
    fun emptyState_showsRecentPlaces() {
        composeTestRule.setContent {
            KccTheme {
                NavigationSearchScreen(
                    mapSurface = StubMapSurface(),
                    searchClient = FakeClient(emptyList(), null),
                    originProvider = { LatLng(12.0757, 57.4874) },
                    onClose = {},
                    onStartNavigation = { _, _ -> },
                    recentStore = InMemoryRecentSearchesStore(listOf(suggestion)),
                )
            }
        }

        // With an empty query, the recent-places card is shown up front.
        composeTestRule.onNodeWithText(str(R.string.addressSearch_recentTitle))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(suggestion.name).assertIsDisplayed()
    }
}
