package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import java.text.NumberFormat

/** One aggregate reading surface; never compress scaled labels into fixed-height tiles. */
@Composable
internal fun ClubStatistics(stats: ClubStats) {
    val locale = clubLocale()
    val numbers = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    val values = listOf(
        stringResource(R.string.clubs_drives) to NumberFormat.getIntegerInstance(locale).format(stats.driveCount),
        stringResource(R.string.clubs_distance) to numbers.format(stats.distanceMeters / 1000),
        stringResource(R.string.clubs_duration) to numbers.format(stats.durationSeconds / 3600.0),
    )
    Text(stringResource(R.string.clubs_statistics), style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth().testTag("club-statistics"), colors = clubCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 280.dp && LocalDensity.current.fontScale <= 1.3f) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        values.forEach { (label, value) ->
                            Statistic(label, value, Modifier.weight(1f))
                        }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    values.forEach { (label, value) -> Statistic(label, value) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.clubs_statsNote), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Statistic(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
