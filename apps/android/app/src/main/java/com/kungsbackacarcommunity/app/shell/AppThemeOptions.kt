package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.LocalThemeController
import com.kungsbackacarcommunity.app.design.ThemePreference
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val MAP_HOME_LAYERS_DAY_TAG = "map_home_layers_day"
const val MAP_HOME_LAYERS_AUTOMATIC_TAG = "map_home_layers_automatic"
const val APP_THEME_DAY_START_TAG = "app_theme_day_start"
const val APP_THEME_NIGHT_START_TAG = "app_theme_night_start"
const val APP_THEME_TIME_CONFIRM_TAG = "app_theme_time_confirm"
const val APP_THEME_TIME_CANCEL_TAG = "app_theme_time_cancel"

/** Shared by the browsing and turn-by-turn layer sheets. No separate map override. */
@Composable
internal fun AppThemeOptions() {
    val controller = LocalThemeController.current
    val schedule = controller.schedule
    var editingDay by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    Text(stringResource(R.string.app_theme_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.app_theme_help), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (LocalDensity.current.fontScale > 1.25f) 2 else 3
        val optionWidth = (maxWidth - KccSpacing.s2 * (columns - 1).toFloat()) / columns.toFloat()
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            MapAppearanceChip(
                label = stringResource(R.string.shell_layersAppearanceDay), icon = Icons.Filled.LightMode,
                selected = controller.preference == ThemePreference.LIGHT,
                onClick = { controller.setPreference(ThemePreference.LIGHT) },
                modifier = Modifier.width(optionWidth).testTag(MAP_HOME_LAYERS_DAY_TAG),
            )
            MapAppearanceChip(
                label = stringResource(R.string.shell_layersAppearanceNight), icon = Icons.Filled.DarkMode,
                selected = controller.preference == ThemePreference.DARK,
                onClick = { controller.setPreference(ThemePreference.DARK) },
                modifier = Modifier.width(optionWidth).testTag(MAP_HOME_LAYERS_NIGHT_TAG),
            )
            MapAppearanceChip(
                label = stringResource(R.string.app_theme_automatic), icon = Icons.Filled.BrightnessAuto,
                selected = controller.preference == ThemePreference.AUTOMATIC,
                onClick = { controller.setPreference(ThemePreference.AUTOMATIC) },
                modifier = Modifier.width(optionWidth).testTag(MAP_HOME_LAYERS_AUTOMATIC_TAG),
            )
        }
    }
    if (controller.preference == ThemePreference.AUTOMATIC) {
        Text(stringResource(R.string.app_theme_schedule_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(
            onClick = { editingDay = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(APP_THEME_DAY_START_TAG),
        ) {
            Text(stringResource(R.string.app_theme_day_from, schedule.dayStart.format(timeFormat)))
        }
        TextButton(
            onClick = { editingDay = false },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(APP_THEME_NIGHT_START_TAG),
        ) {
            Text(stringResource(R.string.app_theme_night_from, schedule.nightStart.format(timeFormat)))
        }
    }
    editingDay?.let { day ->
        ThemeTimeDialog(
            initial = if (day) schedule.dayStart else schedule.nightStart,
            other = if (day) schedule.nightStart else schedule.dayStart,
            title = stringResource(if (day) R.string.app_theme_day_start else R.string.app_theme_night_start),
            onDismiss = { editingDay = null },
            onSave = { time ->
                controller.setSchedule(if (day) schedule.copy(dayStart = time) else schedule.copy(nightStart = time))
                editingDay = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeTimeDialog(initial: LocalTime, other: LocalTime, title: String, onDismiss: () -> Unit, onSave: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    val selectedTime = LocalTime.of(state.hour, state.minute)
    val sameTime = selectedTime == other
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                TimeInput(state = state)
                if (sameTime) Text(stringResource(R.string.app_theme_different_times), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sameTime,
                onClick = { onSave(selectedTime) },
                modifier = Modifier.testTag(APP_THEME_TIME_CONFIRM_TAG),
            ) { Text(stringResource(R.string.app_theme_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(APP_THEME_TIME_CANCEL_TAG)) {
                Text(stringResource(R.string.app_theme_cancel))
            }
        },
    )
}
