package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.incidents.IncidentAgeFilter
import com.kungsbackacarcommunity.app.incidents.IncidentAgeOption

/** Keep every age choice visible; use more rows when labels need room at the user's text size. */
@Composable
internal fun LayerAlertAgeOptions(
    maxAge: IncidentAgeOption,
    onMaxAgeChange: (IncidentAgeOption) -> Unit,
) {
    val options = IncidentAgeFilter.orderedOptions
    val labels = options.map { incidentAgeOptionLabel(it) }
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidth = with(density) {
        labels.maxOf { measurer.measure(it, style).size.width }.toDp()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(MAP_HOME_LAYERS_ALERT_AGE_TAG)) {
        // FilterChip reserves 16dp on either side of its label. Do not shrink text
        // or rely on horizontal gestures to discover the remaining choices.
        val cellWidth = labelWidth + 32.dp
        val columns = when {
            maxWidth >= cellWidth * 4 + KccSpacing.s2 * 3 -> 4
            maxWidth >= cellWidth * 2 + KccSpacing.s2 -> 2
            else -> 1
        }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            options.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                    row.forEach { option ->
                        FilterChip(
                            selected = maxAge == option,
                            onClick = { onMaxAgeChange(option) },
                            label = {
                                Text(
                                    labels[options.indexOf(option)],
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .testTag("${MAP_HOME_LAYERS_ALERT_AGE_TAG}_${option.name}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun incidentAgeOptionLabel(option: IncidentAgeOption): String = stringResource(
    when (option) {
        IncidentAgeOption.DAYS_3 -> R.string.shell_layersAlertAge3d
        IncidentAgeOption.WEEK_1 -> R.string.shell_layersAlertAge1w
        IncidentAgeOption.DAYS_30 -> R.string.shell_layersAlertAge30d
        IncidentAgeOption.ALL -> R.string.shell_layersAlertAgeAll
    },
)
