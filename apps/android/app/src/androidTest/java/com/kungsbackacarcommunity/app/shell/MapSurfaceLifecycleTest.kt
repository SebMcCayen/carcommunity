package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.navigation.NAV_SEARCH_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The map surface's lifecycle contract: it stays composed across tabs, search
 * and routes (never a blank rebuild flash), stands down only when something
 * OPAQUE covers it, delivers gestures to the map, and raises the place-actions
 * menu from a long-press / tap. Part of the map-first shell suite split out of
 * `MapFirstShellTest` (see [MapFirstShellTestSupport]).
 */
@RunWith(AndroidJUnit4::class)
class MapSurfaceLifecycleTest : MapFirstShellTestSupport() {

    /** Opens the address-search overlay the way a user does: expand the bar, tap it. */
    private fun openNavSearch() {
        composeTestRule.onNodeWithTag(MAP_HOME_SEARCH_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.shell_searchHint)).performClick()
        composeTestRule.onNodeWithTag(NAV_SEARCH_TEST_TAG).assertExists()
    }

    /**
     * The map home must NOT be disposed when the user visits another tab.
     *
     * Leaving it used to unmount the map, which on the real surface tears the
     * Mapbox MapView down and rebuilds it (style reload and all) on the way
     * back — the window has nothing to show for those frames, which is the
     * blank blink this guards against. Asserted through the stub's
     * composition counter: one entry for the whole round-trip, not one per
     * visit to the Map tab.
     */
    @Test
    fun switchingTabs_keepsTheMapComposed() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }

        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabGarage)).performClick()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabMap)).performClick()

        // Still the SAME map: had it been disposed on the way to Garage, coming
        // back would have entered the composition a second time.
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }
    }

    /**
     * The flip side of keeping the map alive: a map nobody can see must not keep
     * pulsing its puck and drawing GPS fixes, so the shell stands it down while
     * something OPAQUE covers it and brings it back when it is visible again.
     *
     * The opaque cover here is a full-screen ROUTE, not a tab: History, Social
     * and Garage are translucent panels now and deliberately keep the map live
     * (see [translucentPanelTab_keepsTheMapLive]).
     */
    @Test
    fun coveringTheMap_deactivatesIt_andReturningReactivatesIt() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)
        composeTestRule.runOnIdle { assertTrue(surface.isActive) }

        composeTestRule.onNodeWithTag(MAP_HOME_MORE_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.shell_moreSettings)).performClick()
        composeTestRule.runOnIdle { assertFalse(surface.isActive) }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { assertTrue(surface.isActive) }
    }

    /**
     * The reported bug: pressing the search bar flashed white, and so did leaving
     * it.
     *
     * The map home and the address search each used to call MapSurface.Content,
     * and the shell picked between them — so opening the search DISPOSED the map
     * home's MapView (AndroidView.onRelease -> MapView.onDestroy) and the search
     * built a brand-new one, re-running loadStyle(STANDARD) from scratch; closing
     * did the same in reverse. Between a new SurfaceView attaching and its first
     * GL frame there is nothing to show, and that gap lasts a whole style load —
     * that is the flash, once each way.
     *
     * Guarded exactly as the tab case is: ONE entry into the composition for the
     * whole round-trip. On the real surface each entry is a fresh MapView + style
     * load, so "still 1" is precisely "nothing was rebuilt, so nothing can flash".
     */
    @Test
    fun openingAndClosingSearch_keepsTheMapComposed() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }

        openNavSearch()
        // The search is up, over the SAME map — not a second one.
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }

        // Leave the search the way a user does.
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()

        // Still the same map: had it been disposed either way, this would be 2 or 3.
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }
    }

    /**
     * Holding a finger on the map now raises the place-actions MENU (navigate /
     * copy position / save) in front of the navigate-here flow — and "Navigate
     * here" then opens the very same search overlay the gesture used to raise
     * directly. The white-flash regression must stay fixed throughout: neither the
     * menu nor the navigate step may rebuild the map (contentCompositions == 1).
     */
    @Test
    fun longPressingTheMap_opensThePlaceMenu_thenNavigatePreview_withoutRebuildingTheMap() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }

        // The gesture the real surface publishes when the user holds the map.
        composeTestRule.runOnIdle { surface.emitLongPress(MapPoint(12.0757, 57.4874)) }
        composeTestRule.waitForIdle()

        // Step 1: the place-actions menu, not the search overlay, and no rebuild.
        composeTestRule.onNodeWithTag(PLACE_ACTIONS_SHEET_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(NAV_SEARCH_TEST_TAG).assertDoesNotExist()
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }

        // Step 2: "Navigate here" reaches the existing navigate-here preview.
        composeTestRule.onNodeWithTag(PLACE_ACTIONS_NAVIGATE_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(NAV_SEARCH_TEST_TAG).assertExists()
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }
    }

    /**
     * A single tap on a place the basemap draws reaches the SAME menu a long-press
     * does — that is the whole point of the two gestures sharing one hook — and
     * carries the place's NAME (shown in the menu), not a generic dropped pin.
     * "Navigate here" then opens the named preview.
     */
    @Test
    fun tappingAPlace_opensTheSameMenu_named() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)

        composeTestRule.runOnIdle {
            surface.emitPlaceTap(MapPoint(12.0757, 57.4874), name = "Bilverkstan")
        }
        composeTestRule.waitForIdle()

        // Same menu as the long-press, showing the tapped place BY NAME.
        composeTestRule.onNodeWithTag(PLACE_ACTIONS_SHEET_TEST_TAG).assertExists()
        composeTestRule.onAllNodesWithText("Bilverkstan").onFirst().assertExists()

        // "Navigate here" reaches the named preview, not a generic dropped pin.
        composeTestRule.onNodeWithTag(PLACE_ACTIONS_NAVIGATE_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(NAV_SEARCH_TEST_TAG).assertExists()
        composeTestRule.onAllNodesWithText("Bilverkstan").onFirst().assertExists()
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }
    }

    /**
     * The search draws over a map the user can still SEE (it shows the route and
     * the puck), so unlike a tab or a route it must not stand the surface down.
     * This is the distinction the shell's single `mapCover` exists to keep: the
     * map home's chrome steps back, the map itself stays live.
     */
    @Test
    fun searchOverlay_leavesTheMapActive_unlikeATab() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)
        composeTestRule.runOnIdle { assertTrue(surface.isActive) }

        openNavSearch()
        composeTestRule.runOnIdle {
            assertTrue("the search shows the live map behind it", surface.isActive)
        }

        // A full-screen route, by contrast, hides it entirely — so that one does
        // stand it down. (A non-Map TAB no longer does: all three are translucent
        // panels the map shows through.)
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(MAP_HOME_MORE_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.shell_moreSettings)).performClick()
        composeTestRule.runOnIdle { assertFalse(surface.isActive) }
    }

    /**
     * A full-screen route hides the map completely, so it must stand the surface
     * down — but it must NOT dispose it. Routes were the gap left open when the
     * tab case was fixed; the map now outlives them too.
     */
    @Test
    fun openingARoute_standsTheMapDown_butKeepsItComposed() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)

        composeTestRule.onNodeWithTag(MAP_HOME_MORE_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.shell_moreSettings)).performClick()
        composeTestRule.onNodeWithText(str(R.string.settingsMenu_title)).assertIsDisplayed()

        composeTestRule.runOnIdle {
            assertFalse("a hidden map must not keep burning GPS/GPU", surface.isActive)
            assertEquals("but it must not be rebuilt either", 1, surface.contentCompositions)
        }
    }

    /**
     * Back on another tab must NOT be intercepted by the covered map.
     *
     * The map home stays composed while another tab is shown, which also keeps
     * its `BackHandler(enabled = searchExpanded)` registered with the activity's
     * dispatcher — visibility has no bearing on that. So a search bar left
     * expanded on the Map tab would go on eating Back presses from Social, and
     * because MapHome's handler is added AFTER the shell's it wins: Back would
     * appear to do nothing (it collapses a search bar nobody can see) instead of
     * returning to the Map tab.
     */
    @Test
    fun backOnAnotherTab_isNotSwallowedByTheCoveredMapSearch() {
        setShell()
        // Expand the map's search bar, then leave the Map tab with it expanded.
        composeTestRule.onNodeWithTag(MAP_HOME_SEARCH_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.shell_searchHint)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabSocial)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // Back belongs to the visible tab: it returns to the Map tab
        // (ShellNavigation.onBack), rather than being swallowed by the map.
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }

    /**
     * The v0.8.3 regression, pinned: a drag over open map area must REACH the map
     * surface.
     *
     * The camera's follow / 10-second idle-return logic was already correct (see
     * CameraFollowControllerTest) and is driven entirely by the Mapbox gesture
     * listeners — so it could never release, because the gestures never arrived.
     * The shell drew its pages inside a Material3 Scaffold, and Scaffold wraps its
     * content in a Surface whose empty `pointerInput {}` exists purely to block
     * touch propagation to whatever is drawn beneath it. The map is composed BELOW
     * that frame, so every pan died in the Scaffold and the camera could only be
     * moved programmatically: "locked to my location".
     *
     * This asserts the delivery path itself, which is the half that broke. The
     * swipe is aimed at the LEFT-centre of the map, derived from the map home's
     * measured bounds: clear of the search bar at the top, of the right-side
     * floating controls, and of the bottom bar — i.e. genuinely open map.
     */
    @Test
    fun dragOverOpenMap_reachesTheMapSurface() {
        val surface = StubMapSurface(initialState = MapLoadState.Loaded, autoLoad = false)
        setShell(mapSurface = surface)
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
        assertEquals("no gesture should have been delivered yet", 0, surface.panGestureCount)

        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).performTouchInput {
            // Node-relative and density-independent: a horizontal drag across the
            // left quarter of the map, at its vertical midpoint.
            swipe(
                start = Offset(width * 0.2f, height / 2f),
                end = Offset(width * 0.6f, height / 2f),
            )
        }
        composeTestRule.waitForIdle()

        // Asserted as "at least one", not an exact count: the invariant under test
        // is that the drag REACHED the map, and how many drag starts one swipe
        // decomposes into is incidental to that. Pre-fix this is 0, so the teeth
        // are unaffected.
        assertTrue(
            "a drag over open map must reach the map surface - if this is 0 the " +
                "chrome above the map is swallowing pointer events again " +
                "(was ${surface.panGestureCount})",
            surface.panGestureCount > 0,
        )
    }
}
