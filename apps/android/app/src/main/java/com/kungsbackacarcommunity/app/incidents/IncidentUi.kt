package com.kungsbackacarcommunity.app.incidents

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccAlpha
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing

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

/**
 * The drawable carrying a category's glyph on the map marker.
 *
 * Separated from [IncidentMarkerStyle] (which is deliberately Android-free so
 * its legibility rules can be unit-tested off-device) — this is the one place
 * that turns an abstract [IncidentMarkerStyle.Glyph] into a resource id.
 */
@DrawableRes
fun incidentGlyphRes(type: IncidentType): Int =
    when (IncidentMarkerStyle.glyph(type)) {
        IncidentMarkerStyle.Glyph.ACCIDENT -> R.drawable.ic_incident_accident
        IncidentMarkerStyle.Glyph.ROADWORK -> R.drawable.ic_incident_roadwork
        IncidentMarkerStyle.Glyph.HAZARD -> R.drawable.ic_incident_hazard
        IncidentMarkerStyle.Glyph.POLICE -> R.drawable.ic_incident_police
        IncidentMarkerStyle.Glyph.ROAD_CLOSED -> R.drawable.ic_incident_road_closed
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
 * [IncidentType] as the marker it will become — the category disc with its
 * glyph — plus the label. Picking one invokes [onPick] with the chosen type
 * (the host resolves the current location and reports it); tapping outside or
 * Cancel dismisses.
 *
 * Shared by the map home AND the turn-by-turn navigation view, so reporting is
 * the same three taps whether the user is parked or driving.
 *
 * The glyphs are the app's own vector drawables rather than an extended Material
 * icon set, so this still compiles and UI-tests without pulling in a dependency
 * the project does not have.
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
        // Match the map-overlay popups (MapLayersPopup / LiveSharePopup in
        // MapHome.kt): the same translucent surface, corner radius and elevation
        // so the report picker reads as one of the same floating layer rather
        // than an opaque default M3 dialog. Reuses the shared KccAlpha.aeroSurface
        // token so this can never drift out of step with those popups. The
        // dialog keeps M3's default title/text content colours (onSurface /
        // onSurfaceVariant), which is exactly the contrast the other popups use
        // over this same surface, so text stays legible over the live map.
        shape = RoundedCornerShape(KccRadius.lg),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
        tonalElevation = 6.dp,
        title = { Text(stringResource(R.string.incidents_reportTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                Text(
                    text = stringResource(R.string.incidents_reportSubtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = KccSpacing.s2),
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
                .padding(vertical = KccSpacing.s3)
                .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s4),
    ) {
        // The same disc + glyph the map draws, so the row a user picks looks
        // like the marker their report will become. It was a bare colour
        // swatch, which no longer matched the map once markers gained icons.
        Box(
            modifier =
                Modifier
                    .size(KccSpacing.s6)
                    .clip(CircleShape)
                    .background(IncidentPalette.color(type)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(incidentGlyphRes(type)),
                // The row already carries the label as its contentDescription;
                // announcing the category twice would be noise.
                contentDescription = null,
                tint = Color(IncidentMarkerStyle.glyphColorArgb(type)),
                modifier = Modifier.size(KccSpacing.s4),
            )
        }
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
