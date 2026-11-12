package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.ShellBottomBarHeight
import com.kungsbackacarcommunity.app.design.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The layers choice is app-wide and survives disposal of the map subtree. */
@RunWith(AndroidJUnit4::class)
class MapNightModeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dayAndNightChangeBothPaletteAndMapAndSurviveRouteChanges() {
        val store = ThemePreferenceStore(compose.activity)
        store.set(ThemePreference.DARK)
        val surface = StubMapSurface()
        val routeOpen = mutableStateOf(false)
        var background = Color.Unspecified
        compose.setContent {
            val preference by store.preference.collectAsState()
            val controller = object : ThemeController {
                override val preference = preference
                override fun setPreference(preference: ThemePreference) = store.set(preference)
            }
            CompositionLocalProvider(LocalThemeController provides controller) {
                KccTheme(darkTheme = preference.resolveDark(false)) {
                    background = MaterialTheme.colorScheme.background
                    Box(Modifier.fillMaxSize()) {
                        if (routeOpen.value) {
                            TextButton(onClick = { routeOpen.value = false }) { Text("Close route") }
                        } else {
                            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = ShellBottomBarHeight)) {
                                MapHome(mapSurface = surface, isLiveSharing = false, participantCount = 0,
                                    userLabel = "Tester", onSearch = {}, onOpenSavedPlaces = {}, moreMenuEntries = emptyList())
                            }
                            TextButton(onClick = { routeOpen.value = true }, modifier = Modifier.align(Alignment.BottomStart)) {
                                Text("Open route")
                            }
                        }
                    }
                }
            }
        }
        fun expect(dark: Boolean) {
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(if (dark) MapMode.Night else MapMode.Day, surface.mapMode.value)
                assertEquals(if (dark) KccDarkColors.pageBackground else KccLightColors.pageBackground, background)
            }
        }
        expect(true)
        compose.onNodeWithTag(MAP_HOME_LAYERS_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(MAP_HOME_LAYERS_DAY_TAG).performScrollTo().performClick()
        expect(false)
        // Opening a route disposes the map and its sheet, as the real shell does.
        compose.runOnIdle { routeOpen.value = true }
        compose.waitForIdle()
        assertEquals(KccLightColors.pageBackground, background)
        compose.onNodeWithText("Close route").performClick()
        expect(false)
        assertEquals(ThemePreference.LIGHT, ThemePreferenceStore(compose.activity).preference.value)
        compose.onNodeWithTag(MAP_HOME_LAYERS_TAG).performClick()
        compose.onNodeWithTag(MAP_HOME_LAYERS_NIGHT_TAG).performScrollTo().performClick()
        expect(true)
    }
}
