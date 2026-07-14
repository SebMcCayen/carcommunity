package com.kungsbackacarcommunity.app.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/**
 * Shared session-duration picker: a full-width row of selectable duration chips
 * (1h / 2h / 4h) mapping onto [LiveSessionDuration].
 *
 * Single source of truth for the duration options + their selection/enabled
 * rendering, reused by both the live-share popup on the map home
 * (`shell/MapHome`) and the full [LiveLocationScreen], so the two never drift.
 * The selected option renders filled, the others outlined; [enabled] gates all
 * options while an action is in flight (the popup passes it always-enabled since
 * it has no busy state).
 */
@Composable
internal fun LiveDurationPicker(
    selected: LiveSessionDuration,
    enabled: Boolean,
    onSelect: (LiveSessionDuration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveDurationOption(R.string.liveLocation_duration1h, LiveSessionDuration.ONE_HOUR, selected, enabled, onSelect)
        LiveDurationOption(R.string.liveLocation_duration2h, LiveSessionDuration.TWO_HOURS, selected, enabled, onSelect)
        LiveDurationOption(R.string.liveLocation_duration4h, LiveSessionDuration.FOUR_HOURS, selected, enabled, onSelect)
    }
}

/** A single selectable session-duration chip within [LiveDurationPicker]. */
@Composable
private fun RowScope.LiveDurationOption(
    labelRes: Int,
    duration: LiveSessionDuration,
    selected: LiveSessionDuration,
    enabled: Boolean,
    onSelect: (LiveSessionDuration) -> Unit,
) {
    val label = stringResource(labelRes)
    if (duration == selected) {
        Button(onClick = { onSelect(duration) }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(text = label, textAlign = TextAlign.Center)
        }
    } else {
        OutlinedButton(onClick = { onSelect(duration) }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(text = label, textAlign = TextAlign.Center)
        }
    }
}
