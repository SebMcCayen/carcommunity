package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.ShellBottomBarHeight
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the reported bug: "in the app it changes back to Night
 * mode when navigating around the app".
 *
 * The shell renders the map home in the `else` branch of its route switch, so
 * opening any full-screen route (Settings, Garage, a chat...) DISPOSES MapHome.
 * The manual day/night override used to be `rememberSaveable` state inside
 * MapHome — which survives rotation but NOT disposal — so navigating away and
 * back dropped the user's choice and the follow-the-theme effect immediately
 * re-applied Night on a dark-themed device.
 *
 * The harness below reproduces that exact structure (`if (routeOpen) ... else
 * ... MapHome`) around the REAL MapHome and a real StubMapSurface, under a
 * forced-dark theme so "follow the theme" means Night. [nightChoiceSurvives...]
 * is the fix; [nightChoiceIsLostWhenTheStateIsNotHoisted] pins the OLD broken
 * behaviour, so the pair proves this test can actually detect the bug rather
 * than passing either way.
 */
@RunWith(AndroidJUnit4::class)
class MapNightModeNavigationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val OPEN_ROUTE = "open route"
        const val CLOSE_ROUTE = "close route"
    }

    /** The saver the shell uses for the nullable override. */
    private fun mapModeSaver() = Saver<MapMode?, String>(
        save = { it?.name },
        restore = { saved -> MapMode.entries.find { it.name == saved } },
    )

    /**
     * Mirrors the shell: a full-screen route replaces the map subtree entirely.
     *
     * @param hoisted when true the override is owned ABOVE the route switch (the
     *   fix); when false MapHome keeps it internally (the old behaviour).
     */
    @Composable
    private fun Harness(surface: MapSurface, hoisted: Boolean) {
        // Forced dark so the map's "follow the app theme" default is Night, which
        // is the condition under which the bug is visible.
        KccTheme(darkTheme = true) {
            var routeOpen by rememberSaveable { mutableStateOf(false) }
            val override: MutableState<MapMode?> =
                rememberSaveable(stateSaver = mapModeSaver()) { mutableStateOf(null) }

            // Framed the way the shell frames it. MapHome's floating controls are
            // positioned against its own bounds, so without a fillMaxSize parent
            // and the shell's bottom inset they land outside the injectable area
            // and clicks fail with "Failed to inject touch input".
            Box(modifier = Modifier.fillMaxSize()) {
                if (routeOpen) {
                    TextButton(onClick = { routeOpen = false }) { Text(CLOSE_ROUTE) }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .padding(bottom = ShellBottomBarHeight),
                    ) {
                        MapHome(
                            mapSurface = surface,
                            nightModeOverrideState = if (hoisted) override else null,
                            isLiveSharing = false,
                            participantCount = 0,
                            userLabel = "Tester",
                            onSearch = {},
                            onOpenSavedPlaces = {},
                            moreMenuEntries = emptyList(),
                        )
                    }
                    // Stands in for the shell's route switch. Emitted LAST and
                    // aligned bottom-start so it draws above the map chrome and
                    // clear of the right-hand floating control stack.
                    TextButton(
                        onClick = { routeOpen = true },
                        modifier = Modifier.align(Alignment.BottomStart),
                    ) { Text(OPEN_ROUTE) }
                }
            }
        }
    }

    /** Opens the layers popup and switches "Night mode" off, leaving Day. */
    private fun turnNightModeOff() {
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_TAG).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_NIGHT_TAG).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
    }

    private fun navigateAwayAndBack() {
        composeTestRule.onNodeWithText(OPEN_ROUTE).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(CLOSE_ROUTE).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun nightChoiceSurvivesNavigatingAwayAndBack() {
        val surface = StubMapSurface()
        composeTestRule.setContent { Harness(surface, hoisted = true) }
        composeTestRule.waitForIdle()

        // Dark theme -> the map starts on Night by default.
        assertEquals(MapMode.Night, surface.mapMode.value)

        turnNightModeOff()
        assertEquals(MapMode.Day, surface.mapMode.value)

        navigateAwayAndBack()

        // THE BUG: this used to be Night again.
        assertEquals(
            "the manual Day choice must survive navigation",
            MapMode.Day,
            surface.mapMode.value,
        )
    }

    /**
     * Guards the test itself. Without hoisting, MapHome's disposal drops the
     * override and the map snaps back to Night — the behaviour Seb reported. If
     * this ever starts passing, the harness has stopped exercising the disposal
     * path and the test above is no longer proving anything.
     */
    @Test
    fun nightChoiceIsLostWhenTheStateIsNotHoisted() {
        val surface = StubMapSurface()
        composeTestRule.setContent { Harness(surface, hoisted = false) }
        composeTestRule.waitForIdle()

        turnNightModeOff()
        assertEquals(MapMode.Day, surface.mapMode.value)

        navigateAwayAndBack()

        assertEquals(
            "un-hoisted state is expected to reset — this is the old bug",
            MapMode.Night,
            surface.mapMode.value,
        )
    }

    /**
     * The other half of the fix: the map's default follows the APP theme, not
     * the raw system setting. In a light-themed app the map must start on Day
     * even on a device whose system theme is dark — otherwise choosing Light in
     * bright sunshine would still leave a night-styled map.
     */
    @Test
    fun mapDefaultsToDayWhenTheAppThemeIsLight() {
        val surface = StubMapSurface()
        composeTestRule.setContent {
            KccTheme(darkTheme = false) {
                MapHome(
                    mapSurface = surface,
                    isLiveSharing = false,
                    participantCount = 0,
                    userLabel = "Tester",
                    onSearch = {},
                    onOpenSavedPlaces = {},
                    moreMenuEntries = emptyList(),
                )
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(MapMode.Day, surface.mapMode.value)
    }
}
