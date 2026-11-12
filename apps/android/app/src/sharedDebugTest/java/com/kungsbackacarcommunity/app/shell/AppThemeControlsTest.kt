package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.*
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Same controls run on Robolectric and a native emulator. */
@RunWith(AndroidJUnit4::class)
class AppThemeControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Controller : ThemeController {
        private val mode = mutableStateOf(ThemePreference.AUTOMATIC)
        private val times = mutableStateOf(AutomaticThemeSchedule.DEFAULT)
        override val preference get() = mode.value
        override val schedule get() = times.value
        override fun setPreference(preference: ThemePreference) { mode.value = preference }
        override fun setSchedule(schedule: AutomaticThemeSchedule) { times.value = schedule }
    }
    private val controller = Controller()

    private fun render(fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalThemeController provides controller,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                KccTheme(darkTheme = controller.preference == ThemePreference.DARK) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { AppThemeOptions() }
                }
            }
        }
    }

    @Test fun threeChoicesRemainUsableAtLargeFontSize() {
        render(2f)
        val tags = listOf(MAP_HOME_LAYERS_DAY_TAG, MAP_HOME_LAYERS_NIGHT_TAG, MAP_HOME_LAYERS_AUTOMATIC_TAG)
        tags.forEach { selected ->
            compose.onNodeWithTag(selected).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
            tags.filter { it != selected }.forEach { compose.onNodeWithTag(it).assertIsNotSelected() }
        }
        compose.onNodeWithTag(APP_THEME_DAY_START_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(APP_THEME_NIGHT_START_TAG).performScrollTo().assertIsDisplayed()
    }

    private fun enterTime(hour: String, minute: String) {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement(hour)
        fields[1].performTextReplacement(minute)
    }

    @Test fun bothTimesCanBeEditedAndCancelDoesNotSave() {
        render(2f)
        compose.onNodeWithTag(APP_THEME_DAY_START_TAG).performScrollTo().performClick()
        enterTime("08", "30")
        compose.onNodeWithTag(APP_THEME_TIME_CONFIRM_TAG).performClick()
        assertEquals(LocalTime.of(8, 30), controller.schedule.dayStart)
        compose.onNodeWithTag(APP_THEME_NIGHT_START_TAG).performScrollTo().performClick()
        enterTime("21", "15")
        compose.onNodeWithTag(APP_THEME_TIME_CONFIRM_TAG).performClick()
        assertEquals(LocalTime.of(21, 15), controller.schedule.nightStart)
        compose.onNodeWithTag(APP_THEME_DAY_START_TAG).performScrollTo().performClick()
        enterTime("09", "45")
        compose.onNodeWithTag(APP_THEME_TIME_CANCEL_TAG).performClick()
        assertEquals(LocalTime.of(8, 30), controller.schedule.dayStart)
        compose.onNodeWithTag(MAP_HOME_LAYERS_DAY_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(APP_THEME_DAY_START_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MAP_HOME_LAYERS_AUTOMATIC_TAG).performScrollTo().performClick()
        assertEquals(AutomaticThemeSchedule(LocalTime.of(8, 30), LocalTime.of(21, 15)), controller.schedule)
    }

    @Test fun equalTimesCannotBeSaved() {
        render()
        compose.onNodeWithTag(APP_THEME_DAY_START_TAG).performScrollTo().performClick()
        enterTime("19", "00")
        compose.onNodeWithTag(APP_THEME_TIME_CONFIRM_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(APP_THEME_TIME_CANCEL_TAG).performClick()
        assertEquals(AutomaticThemeSchedule.DEFAULT, controller.schedule)
    }
}
