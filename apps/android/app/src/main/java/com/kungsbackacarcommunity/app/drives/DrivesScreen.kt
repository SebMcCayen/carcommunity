package com.kungsbackacarcommunity.app.drives

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding
import java.text.DateFormat
import java.util.Date

/** Saved-drives list (Phase 12 slice 12). Read-only; tap a drive to see details. */
@Composable
fun DrivesListScreen(
    state: DrivesState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Re-invokes the drives load; when null the error state shows no retry.
    onRetry: (() -> Unit)? = null,
    // Opens the personal "your driving" stats page. The entry is rendered only
    // when at least one drive is loaded — a zero-drive member sees the empty card
    // instead, so the stats entry never leads to a page of zeroes.
    onShowStats: (() -> Unit)? = null,
) {
    // LazyColumn so an unbounded drive history only composes visible rows
    // (mirrors NotificationsScreen for durable lists).
    AeroLazyPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = aeroLazyContentPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AeroPageTitle(stringResource(R.string.savedDrives_screenTitle))
            }

            when (state) {
                DrivesState.Loading ->
                    item {
                        Text(
                            text = stringResource(R.string.savedDrives_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                is DrivesState.Error ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = stringResource(R.string.savedDrives_error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            if (onRetry != null) {
                                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = stringResource(R.string.savedDrives_retry))
                                }
                            }
                        }
                    }

                is DrivesState.Loaded ->
                    if (state.drives.isEmpty()) {
                        item { EmptyDrives() }
                    } else {
                        if (onShowStats != null) {
                            item { StatsEntryCard(onShowStats) }
                        }
                        items(state.drives, key = { it.rideId }) { drive ->
                            DriveCard(drive, onSelect)
                        }
                    }
            }
        }
    }
}

@Composable
private fun StatsEntryCard(onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.savedDrives_statsEntryTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.savedDrives_statsEntrySubtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyDrives() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.savedDrives_empty),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun DriveCard(drive: SavedDrive, onSelect: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(drive.rideId) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = driveTitle(drive),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    DriveFormatters.formatDistance(drive.distanceMeters) +
                        " · " + DriveFormatters.formatDuration(drive.durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Saved-drive detail (Phase 12 slice 12): server-computed stats + delete. */
@Composable
fun SavedDriveDetailScreen(
    drive: SavedDrive,
    deleteStatus: DriveDeleteStatus,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val dateText = formatDriveDate(drive.startedAtMillis ?: drive.createdAtMillis)
    val timeRangeText = formatDriveTimeRange(drive.startedAtMillis, drive.endedAtMillis)
    val averageSpeed =
        DriveFormatters.effectiveAverageSpeed(
            drive.averageSpeedMetersPerSecond,
            drive.distanceMeters,
            drive.durationSeconds,
        )

    val appName = stringResource(R.string.app_name)
    val distanceText = DriveFormatters.formatDistance(drive.distanceMeters)
    val durationText = DriveFormatters.formatDuration(drive.durationSeconds)
    // Drop the "on <date>" clause entirely when the drive has no timestamp,
    // rather than emitting a bare em dash ("… on —").
    val shareSummary =
        if (dateText != null) {
            stringResource(
                R.string.savedDrives_shareSummary,
                distanceText,
                durationText,
                dateText,
                appName,
            )
        } else {
            stringResource(
                R.string.savedDrives_shareSummaryNoDate,
                distanceText,
                durationText,
                appName,
            )
        }

    AeroPage(title = stringResource(R.string.savedDrives_detailTitle), modifier = modifier) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (dateText != null) {
                        StatRow(stringResource(R.string.savedDrives_date), dateText)
                    }
                    if (timeRangeText != null) {
                        StatRow(stringResource(R.string.savedDrives_timeRange), timeRangeText)
                    }
                    StatRow(
                        stringResource(R.string.savedDrives_distance),
                        DriveFormatters.formatDistance(drive.distanceMeters),
                    )
                    StatRow(
                        stringResource(R.string.savedDrives_duration),
                        DriveFormatters.formatDuration(drive.durationSeconds),
                    )
                    StatRow(
                        stringResource(R.string.savedDrives_averageSpeed),
                        DriveFormatters.formatSpeed(averageSpeed),
                    )
                }
            }

            // Map replay is FLAGGED: the recorded route path/GPS points are NOT
            // in the SavedDrive read model (they live in member-gated Cloud
            // Storage and the `rides` doc carries no coordinates), so there is
            // nothing to draw on MapboxMapSurface here. Wiring a real route
            // replay needs a backend/recording follow-up to expose the path to
            // the client; until then a placeholder card explains the gap.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.savedDrives_routeOverview),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.savedDrives_routeOverviewPlaceholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (deleteStatus == DriveDeleteStatus.Failed) {
                Text(
                    text = stringResource(R.string.savedDrives_deleteError),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val shareUnavailable = stringResource(R.string.savedDrives_shareUnavailable)
            OutlinedButton(
                onClick = {
                    val sendIntent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareSummary)
                        }
                    val chooser = Intent.createChooser(sendIntent, null)
                    // A non-Activity context (application) has no task to launch
                    // into, so an Activity launch without FLAG_ACTIVITY_NEW_TASK
                    // throws. Add it defensively; an Activity keeps same-task.
                    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Some devices have no app that handles ACTION_SEND; guard the
                    // launch so a missing handler is a toast, not a hard crash.
                    try {
                        context.startActivity(chooser)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, shareUnavailable, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.savedDrives_shareAction))
            }

            Button(
                onClick = { showConfirm = true },
                enabled = deleteStatus != DriveDeleteStatus.Deleting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.savedDrives_deleteAction))
            }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.savedDrives_deleteConfirmTitle)) },
            text = { Text(stringResource(R.string.savedDrives_deleteConfirmBody)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onDelete(drive.rideId)
                    },
                ) {
                    Text(stringResource(R.string.savedDrives_deleteConfirmAction))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.savedDrives_deleteConfirmCancel))
                }
            },
        )
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

/** Medium locale date (e.g. "Jul 15, 2026") for a drive timestamp, or null. */
private fun formatDriveDate(millis: Long?): String? =
    millis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }

/**
 * "13:05 – 14:00" short-time range when both endpoints are present, else null.
 * Timestamps are backend-provided and nullable; a range where the end is before
 * the start is treated as invalid (returns null) so we never render a backwards
 * range. Equal endpoints render as a single time.
 */
internal fun formatDriveTimeRange(startMillis: Long?, endMillis: Long?): String? {
    if (startMillis == null || endMillis == null) return null
    if (endMillis < startMillis) return null
    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    val start = timeFormat.format(Date(startMillis))
    if (endMillis == startMillis) return start
    return "$start – ${timeFormat.format(Date(endMillis))}"
}

@Composable
private fun driveTitle(drive: SavedDrive): String {
    val title = drive.title?.takeIf { it.isNotBlank() }
    if (title != null) return title
    val millis = drive.createdAtMillis ?: drive.startedAtMillis
    return if (millis != null) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    } else {
        stringResource(R.string.savedDrives_detailTitle)
    }
}
