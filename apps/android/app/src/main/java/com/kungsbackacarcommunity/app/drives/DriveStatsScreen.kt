package com.kungsbackacarcommunity.app.drives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.util.Calendar

/**
 * "Your driving" personal stats (folded over the member's OWN saved drives).
 *
 * Reads no backend of its own: [drives] is the exact list the History route
 * already loaded (an owner query with no `limit`, so it is the member's whole
 * history), and every figure is a pure fold via [DriveStatsCalculator]. All
 * distance/duration/speed rendering goes through [DriveFormatters]; the drive
 * count is a bare integer (not a formatter concern).
 */
@Composable
fun DriveStatsScreen(
    drives: List<SavedDrive>,
    modifier: Modifier = Modifier,
) {
    // Start of the current calendar month in the device's local time zone,
    // recomputed each composition (deliberately NOT remembered) so the "this
    // month" boundary follows a month rollover if the screen stays composed across
    // midnight on the 1st. The value is deterministic within a month, so the keyed
    // `stats` fold below still only recomputes when the drives or the month itself
    // change. The Calendar/time-zone concern lives here at the composable edge so
    // the fold ([DriveStatsCalculator.compute]) stays pure and deterministic.
    val monthStartMillis =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    val stats = remember(drives, monthStartMillis) { DriveStatsCalculator.compute(drives, monthStartMillis) }

    AeroPage(title = stringResource(R.string.savedDrives_statsTitle), modifier = modifier) {
        if (stats == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.savedDrives_statsEmpty),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
                )
            }
            return@AeroPage
        }

        StatsCard(stringResource(R.string.savedDrives_statsAllTime)) {
            StatRow(
                stringResource(R.string.savedDrives_statsTotalDrives),
                stats.totalDrives.toString(),
            )
            StatRow(
                stringResource(R.string.savedDrives_statsTotalDistance),
                DriveFormatters.formatDistance(stats.totalDistanceMeters),
            )
            StatRow(
                stringResource(R.string.savedDrives_statsTotalTime),
                DriveFormatters.formatDuration(stats.totalDurationSeconds),
            )
            StatRow(
                stringResource(R.string.savedDrives_statsLongest),
                DriveFormatters.formatDistance(stats.longestDriveMeters),
            )
            StatRow(
                stringResource(R.string.savedDrives_statsAverage),
                DriveFormatters.formatDistance(stats.averageDriveMeters),
            )
            StatRow(
                stringResource(R.string.savedDrives_statsFastest),
                DriveFormatters.formatSpeed(stats.fastestAverageSpeedMps),
            )
        }

        StatsCard(stringResource(R.string.savedDrives_statsThisMonth)) {
            StatRow(
                stringResource(R.string.savedDrives_statsTotalDrives),
                stats.thisMonthDrives.toString(),
            )
            StatRow(
                stringResource(R.string.savedDrives_statsTotalDistance),
                DriveFormatters.formatDistance(stats.thisMonthDistanceMeters),
            )
        }
    }
}

@Composable
private fun StatsCard(header: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
