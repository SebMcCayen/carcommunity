package com.kungsbackacarcommunity.app.design

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.kungsbackacarcommunity.app.shell.MapLayersPreferenceStore
import com.kungsbackacarcommunity.app.shell.MapMode
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class ThemeMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("app_theme_preference", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("map_layers_preference", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun explicitMapChoiceWinsOnceAndNewChoiceSurvivesReopening() {
        prefs.edit().putString("mode", "DARK").commit()
        MapLayersPreferenceStore(context).writeNightMode(MapMode.Day)
        val store = ThemePreferenceStore(context)
        assertEquals(ThemePreference.LIGHT, store.preference.value)
        assertEquals(null, MapLayersPreferenceStore(context).readNightMode())
        store.set(ThemePreference.AUTOMATIC)
        val schedule = AutomaticThemeSchedule(LocalTime.of(8, 45), LocalTime.of(22, 15))
        store.setSchedule(schedule)
        val reopened = ThemePreferenceStore(context)
        assertEquals(ThemePreference.AUTOMATIC, reopened.preference.value)
        assertEquals(schedule, reopened.schedule.value)
    }

    @Test fun legacyNightChoiceBecomesAppWideDark() {
        MapLayersPreferenceStore(context).writeNightMode(MapMode.Night)
        assertEquals(ThemePreference.DARK, ThemePreferenceStore(context).preference.value)
    }

    @Test fun explicitAppChoiceIsKeptWithoutMapOverride() {
        prefs.edit().putString("mode", "LIGHT").commit()
        assertEquals(ThemePreference.LIGHT, ThemePreferenceStore(context).preference.value)
    }

    @Test fun newInstallAndOldSystemChoiceUseAutomatic() {
        assertEquals(ThemePreference.AUTOMATIC, ThemePreferenceStore(context).preference.value)
        reset()
        prefs.edit().putString("mode", "SYSTEM").commit()
        assertEquals(ThemePreference.AUTOMATIC, ThemePreferenceStore(context).preference.value)
    }

    @Test fun corruptTimesRecoverToDefault() {
        prefs.edit().putInt("day_start_minute", -1).putInt("night_start_minute", 600).commit()
        assertEquals(AutomaticThemeSchedule.DEFAULT, ThemePreferenceStore(context).schedule.value)
    }
    @Test fun corruptModeAfterMigrationStillSelectsAutomatic() {
        ThemePreferenceStore(context)
        prefs.edit().putString("mode", "UNKNOWN").commit()
        assertEquals(ThemePreference.AUTOMATIC, ThemePreferenceStore(context).preference.value)
    }

}
