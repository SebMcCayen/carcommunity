package com.kungsbackacarcommunity.app.design

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.shell.*
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real store, theme resolver, layers controls and map together. */
@RunWith(AndroidJUnit4::class)
class AutomaticMapThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun layersChoicesChangeMapAndPaletteAndSurviveNavigationAndRestart() {
        var now = ZonedDateTime.parse("2026-10-27T08:29:00+01:00[Europe/Stockholm]")
        val clock = AutomaticThemeClock(compose.activity) { now }
        val store = ThemePreferenceStore(compose.activity)
        store.set(ThemePreference.AUTOMATIC)
        store.setSchedule(AutomaticThemeSchedule(LocalTime.of(8, 30), LocalTime.of(21, 15)))
        val surface = mutableStateOf(StubMapSurface())
        val showMap = mutableStateOf(true)
        var background = Color.Unspecified
        compose.setContent {
            val preference by store.preference.collectAsState()
            val schedule by store.schedule.collectAsState()
            val dark = rememberResolvedDarkTheme(preference, false, compose.activity.lifecycle, clock, schedule)
            val controller = object : ThemeController {
                override val preference = preference
                override val schedule = schedule
                override fun setPreference(preference: ThemePreference) = store.set(preference)
                override fun setSchedule(schedule: AutomaticThemeSchedule) = store.setSchedule(schedule)
            }
            CompositionLocalProvider(LocalThemeController provides controller) {
                KccTheme(darkTheme = dark) {
                    background = MaterialTheme.colorScheme.background
                    if (showMap.value) MapHome(
                        mapSurface = surface.value, isLiveSharing = false, participantCount = 0,
                        userLabel = "Tester", onSearch = {}, onOpenSavedPlaces = {}, moreMenuEntries = emptyList(),
                    )
                }
            }
        }
        fun expect(dark: Boolean) {
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(if (dark) MapMode.Night else MapMode.Day, surface.value.mapMode.value)
                assertEquals(if (dark) KccDarkColors.pageBackground else KccLightColors.pageBackground, background)
            }
        }
        fun tick(hour: Int, minute: Int) {
            now = now.withHour(hour).withMinute(minute)
            compose.activity.sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
        }
        fun choose(tag: String) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
        }
        expect(true)
        tick(8, 30)
        expect(false)
        compose.onNodeWithTag(MAP_HOME_LAYERS_TAG).performClick()
        choose(MAP_HOME_LAYERS_NIGHT_TAG)
        expect(true)
        tick(12, 0)
        expect(true)
        choose(MAP_HOME_LAYERS_DAY_TAG)
        expect(false)
        tick(21, 15)
        expect(false)
        compose.runOnIdle { showMap.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showMap.value = true; surface.value = StubMapSurface() }
        expect(false)
        assertEquals(ThemePreference.LIGHT, ThemePreferenceStore(compose.activity).preference.value)
        compose.onNodeWithTag(MAP_HOME_LAYERS_TAG).performClick()
        choose(MAP_HOME_LAYERS_AUTOMATIC_TAG)
        expect(true)
        // Editing a schedule applies immediately, without waiting for the next minute tick.
        compose.runOnIdle { store.setSchedule(AutomaticThemeSchedule(LocalTime.of(8, 30), LocalTime.of(22, 0))) }
        expect(false)
        tick(22, 0)
        expect(true)
        assertEquals(store.schedule.value, ThemePreferenceStore(compose.activity).schedule.value)
    }
}
