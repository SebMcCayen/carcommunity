package com.kungsbackacarcommunity.app.incidents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/**
 * Category colours for incidents — the single source of truth used both for the
 * map markers ([colorArgb], an ARGB Int the Mapbox surface draws) and the type
 * picker swatches ([color], a Compose [Color]). Kept as one table so a marker
 * and its picker row always match.
 */
object IncidentPalette {
    fun colorArgb(type: IncidentType): Int =
        when (type) {
            IncidentType.ACCIDENT -> 0xFFD32F2F.toInt() // red
            IncidentType.ROADWORK -> 0xFFF57C00.toInt() // orange
            IncidentType.HAZARD -> 0xFFFBC02D.toInt() // amber
            IncidentType.POLICE -> 0xFF1565C0.toInt() // blue
            IncidentType.ROAD_CLOSED -> 0xFF7B1FA2.toInt() // purple
        }

    fun color(type: IncidentType): Color = Color(colorArgb(type))
}

/** Localized label for an incident type. */
@Composable
fun incidentTypeLabel(type: IncidentType): String =
    stringResource(
        when (type) {
            IncidentType.ACCIDENT -> R.string.incidents_typeAccident
            IncidentType.ROADWORK -> R.string.incidents_typeRoadwork
            IncidentType.HAZARD -> R.string.incidents_typeHazard
            IncidentType.POLICE -> R.string.incidents_typePolice
            IncidentType.ROAD_CLOSED -> R.string.incidents_typeRoadClosed
        },
    )

/**
 * The incident-report type picker: a simple dialog listing every
 * [IncidentType] with its category swatch. Picking one invokes [onPick] with
 * the chosen type (the host resolves the current location and reports it);
 * tapping outside or Cancel dismisses.
 *
 * Deliberately icon-free (coloured swatch + label) so it compiles and UI-tests
 * without depending on any extended-icon set.
 */
@Composable
fun IncidentTypePickerDialog(
    onPick: (IncidentType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.incidents_cancel))
            }
        },
        title = { Text(stringResource(R.string.incidents_reportTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.incidents_reportSubtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (type in IncidentType.entries) {
                    IncidentTypeRow(type = type, onClick = { onPick(type) })
                }
            }
        },
    )
}

@Composable
private fun IncidentTypeRow(type: IncidentType, onClick: () -> Unit) {
    val label = incidentTypeLabel(type)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp)
                .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(IncidentPalette.color(type)),
        ) {}
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
