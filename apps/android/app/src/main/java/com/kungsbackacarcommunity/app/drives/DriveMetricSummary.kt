package com.kungsbackacarcommunity.app.drives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/** Existing distance/time facts only. No reward estimates or competitive speed emphasis. */
@Composable
internal fun DriveMetricSummary(
    distance: String,
    duration: String,
    distanceLabel: String = stringResource(R.string.savedDrives_distance),
    durationLabel: String = stringResource(R.string.savedDrives_duration),
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 280.dp && LocalDensity.current.fontScale <= 1.3f) {
            Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                Metric(distanceLabel, distance, Icons.Filled.Timeline, Modifier.weight(1f))
                Metric(durationLabel, duration, Icons.Filled.Schedule, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                Metric(distanceLabel, distance, Icons.Filled.Timeline, Modifier.fillMaxWidth())
                Metric(durationLabel, duration, Icons.Filled.Schedule, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.padding(KccSpacing.s3).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
