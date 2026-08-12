package com.kungsbackacarcommunity.app.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import com.kungsbackacarcommunity.app.testutil.RetryRule
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
    val composeTestRule = createComposeRule()

    // RetryRule OUTSIDE the compose rule: a retry relaunches the Activity /
    // rebuilds the compose hierarchy, self-healing the emulator "Activity did not
    // launch" flake. See RetryRule.
    @get:Rule
    val rules = RetryRule.around(composeTestRule)

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    /**
     * The height the route sheet is currently revealing, read off the tagged
     * steps container — which IS the reveal (its `layout` modifier sets its
     * height to the animated reveal value).
     *
     * Deliberately not the maneuver text's `size.height`: a LazyColumn measures
     * items with an unbounded main axis, so a row keeps its natural height even
     * inside a zero-height viewport, and the clip that hides it is applied by
     * this container. Measured on API 34: collapsed the row is still 43px tall
     * with 0px of it on screen, while this container is 0; expanded the container
     * is 201.
     */
    private fun stepsRevealHeight(): Int =
        composeTestRule
            .onAllNodesWithTag(NAV_ROUTE_STEPS_TEST_TAG)
            .fetchSemanticsNodes()
            .sumOf { it.size.height }

    private fun assertStepsRevealHeight(expected: Int) {
        val nodes =
            composeTestRule.onAllNodesWithTag(NAV_ROUTE_STEPS_TEST_TAG).fetchSemanticsNodes()
        assertEquals("Expected exactly one steps container", 1, nodes.size)
        assertEquals(
            "Steps container should reveal ${expected}px",
            expected,
            nodes.single().size.height,
        )
    }

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
    fun selectingAPlace_opensTheSheetCOLLAPSED_withStartButNoManeuvers() {
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

        // Route resolved → the sheet appears at its PEEK. This is the whole fix:
        // the directions are composed but revealed to zero height, so the map
        // behind shows the entire route instead of being half-covered by a wall
        // of maneuvers nobody asked for yet.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(str(R.string.turnByTurn_start))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(str(R.string.turnByTurn_start)).assertIsDisplayed()
        // The peek REVEALS nothing: the steps container exists but is zero-height.
        //
        // This is asserted on the CONTAINER, not on the maneuver text's own
        // `size.height`. A LazyColumn measures its items with an unbounded main
        // axis, so each row keeps its natural height (43px here) no matter how
        // small the viewport is — the reveal is applied by the ancestor's clip.
        // Asserting `size.height == 0` on the text therefore can never pass, and
        // `size.height > 0` passes even while collapsed; both are useless as
        // detent signals. The container's own height IS the reveal.
        assertStepsRevealHeight(0)
        // ...and the maneuver text, while composed, is clipped out of sight.
        composeTestRule.onNodeWithText("Head north on Main Street").assertIsNotDisplayed()

        // Dragging is not the only way in: the handle is also a tap target, so
        // the directions are reachable without a gesture.
        composeTestRule.onNodeWithTag(NAV_ROUTE_SHEET_HANDLE_TEST_TAG).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            stepsRevealHeight() > 0
        }
        composeTestRule.onNodeWithText(str(R.string.addressSearch_directionsTitle))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Head north on Main Street").assertIsDisplayed()

        // The "Start" CTA is present in BOTH detents and in EVERY build: the host
        // decides how it navigates (in-app Mapbox turn-by-turn when the SDK is
        // bundled, else a maps-app handoff), so the preview never dead-ends —
        // and expanding the directions must never push it off screen.
        composeTestRule.onNodeWithText(str(R.string.turnByTurn_start)).assertIsDisplayed()

        // ...and collapsing again puts the maneuvers away without taking Start
        // with them.
        composeTestRule.onNodeWithTag(NAV_ROUTE_SHEET_HANDLE_TEST_TAG).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            stepsRevealHeight() == 0
        }
        composeTestRule.onNodeWithText("Head north on Main Street").assertIsNotDisplayed()
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

    /**
     * Picking a SECOND destination while the sheet is expanded must re-land at
     * the collapsed peek immediately — never leaving the previous reveal in
     * place, which shows as a tall EMPTY gap because the new route has not
     * resolved yet (`route` is null, so the steps area has no content to draw).
     *
     * `detent` is `rememberSaveable(destination.id)` so it resets on its own; the
     * live pixel `reveal` is a plain `remember` and therefore survives the swap,
     * which is exactly what this pins.
     *
     * The destination is swapped through `initialTarget` (the map's "navigate
     * here" gesture) rather than the search field: it changes `destination.id`
     * with no debounce to wait out, so the assertion lands on the frames right
     * after the swap instead of racing a timer.
     */
    @Test
    fun pickingANewDestinationWhileExpanded_reCollapsesWithNoBlankGap() {
        val alpha = LatLng(longitude = 12.08, latitude = 57.49)
        val beta = LatLng(longitude = 11.97, latitude = 57.71)
        val target = mutableStateOf(alpha)

        composeTestRule.setContent {
            KccTheme {
                NavigationSearchScreen(
                    mapSurface = StubMapSurface(),
                    searchClient = FakeClient(listOf(suggestion), route),
                    originProvider = { LatLng(12.0757, 57.4874) },
                    onClose = {},
                    onStartNavigation = { _, _ -> },
                    initialTarget = target.value,
                    initialTargetName = "Alpha",
                )
            }
        }

        // First destination resolves and the sheet is at its peek.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(str(R.string.turnByTurn_start))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Expand it, and wait until the maneuvers really occupy height.
        composeTestRule.onNodeWithTag(NAV_ROUTE_SHEET_HANDLE_TEST_TAG).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithTag(NAV_ROUTE_STEPS_TEST_TAG)
                .fetchSemanticsNodes()
                .any { it.size.height > 0 }
        }

        // Now pick a DIFFERENT destination. Step the clock by hand from here:
        // letting it free-run would just play any collapse animation out and hide
        // the very frames this is about.
        composeTestRule.mainClock.autoAdvance = false
        target.value = beta

        // Pump a few frames — enough for the swap to compose and lay out, far
        // short of a spring settling from a full reveal to zero.
        repeat(4) { composeTestRule.mainClock.advanceTimeByFrame() }

        val heights =
            composeTestRule
                .onAllNodesWithTag(NAV_ROUTE_STEPS_TEST_TAG)
                .fetchSemanticsNodes()
                .map { it.size.height }
        assertEquals(
            "The steps area must be back at zero height for the new destination; " +
                "a non-zero height here is a blank revealed gap (the new route is " +
                "still null, so there is nothing drawn in it). Heights: $heights",
            emptyList<Int>(),
            heights.filter { it > 0 },
        )

        composeTestRule.mainClock.autoAdvance = true
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
