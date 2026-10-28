package com.kungsbackacarcommunity.app.design

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.shell.MapHome
import com.kungsbackacarcommunity.app.shell.MapMode
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MapHome consumes LocalKccDarkTheme; its own explicit overrides must still win. */
@RunWith(AndroidJUnit4::class)
class AutomaticMapThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mapFollowsScheduleUntilAnExplicitDayOrNightChoice() {
        var now = ZonedDateTime.parse("2026-10-27T06:59:00+01:00[Europe/Stockholm]")
        val clock = AutomaticThemeClock(compose.activity) { now }
        val surface = StubMapSurface()
        val override = mutableStateOf<MapMode?>(null)
        val showMap = mutableStateOf(true)
        compose.setContent {
            val dark = rememberResolvedDarkTheme(
                preference = ThemePreference.AUTOMATIC,
                systemInDark = false,
                lifecycle = compose.activity.lifecycle,
                clock = clock,
            )
            KccTheme(darkTheme = dark) {
                if (showMap.value) {
                    MapHome(
                        mapSurface = surface,
                        nightModeOverrideState = override,
                        isLiveSharing = false,
                        participantCount = 0,
                        userLabel = "Tester",
                        onSearch = {},
                        onOpenSavedPlaces = {},
                        moreMenuEntries = emptyList(),
                    )
                }
            }
        }
        fun expect(mode: MapMode) {
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(mode, surface.mapMode.value) }
        }
        fun tick(hour: Int) {
            now = now.withHour(hour).withMinute(0)
            compose.activity.sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
        }

        expect(MapMode.Night)
        tick(7)
        expect(MapMode.Day)
        compose.runOnIdle { override.value = MapMode.Night }
        expect(MapMode.Night)
        tick(12)
        expect(MapMode.Night)
        compose.runOnIdle { override.value = MapMode.Day }
        tick(19)
        expect(MapMode.Day)
        compose.runOnIdle { showMap.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showMap.value = true }
        expect(MapMode.Day)
        compose.runOnIdle { override.value = null }
        expect(MapMode.Night)
    }
}
