package com.kungsbackacarcommunity.app.drives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * "Your driving" personal stats, now SERVER-AUTHORITATIVE ([drives-stats]).
 *
 * The figures used to be a client-side fold over the History list, which was only
 * correct while that list was fully loaded; once history became tier-gated and
 * paginated (slice B1) the fold would have silently reported "loaded page only".
 * The aggregate is therefore computed server-side over all retained owner drives
 * for every tier, independently of history browsing limits.
 * All distance/duration/speed rendering still
 * goes through [DriveFormatters]; the drive count is a bare integer.
 */
@Composable
fun DriveStatsScreen(
    state: DriveStatsUiState,
    modifier: Modifier = Modifier,
    // Re-invokes the stats load; when null the error state shows no retry.
    onRetry: (() -> Unit)? = null,
) {
    AeroPage(title = stringResource(R.string.savedDrives_statsTitle), modifier = modifier) {
        when (state) {
            DriveStatsUiState.Loading ->
                InfoCard(stringResource(R.string.savedDrives_statsLoading))

            is DriveStatsUiState.Error ->
                Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                    Text(
                        text = stringResource(R.string.savedDrives_statsError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (onRetry != null) {
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(R.string.savedDrives_retry))
                        }
                    }
                }

            is DriveStatsUiState.Loaded ->
                if (state.snapshot.totalDrives == 0) {
                    InfoCard(stringResource(R.string.savedDrives_statsEmpty))
                } else {
                    StatsContent(state.snapshot)
                }
        }
    }
}

@Composable
private fun StatsContent(stats: DriveStatsSnapshot) {
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

@Composable
private fun InfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
        )
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
