package com.kungsbackacarcommunity.app.drives

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.map.DriveRouteMap
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding
import java.text.DateFormat
import java.util.Date

/** Saved-drives list (Phase 12 slice 12). Tap a drive for detail; share or delete inline. */
@Composable
fun DrivesListScreen(
    state: DrivesState,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    deleteStatus: DriveDeleteStatus,
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

            // A delete launched from a row is reconciled by the Firestore
            // snapshot listener (the row simply disappears on success); a
            // failure leaves the row and is surfaced here so the user knows the
            // drive was NOT removed.
            if (deleteStatus == DriveDeleteStatus.Failed) {
                item {
                    Text(
                        text = stringResource(R.string.savedDrives_deleteError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
                            DriveCard(
                                drive = drive,
                                onSelect = onSelect,
                                onDelete = onDelete,
                                deleteInFlight = deleteStatus == DriveDeleteStatus.Deleting,
                            )
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
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
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
private fun DriveCard(
    drive: SavedDrive,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    deleteInFlight: Boolean,
) {
    val context = LocalContext.current
    // The History read model carries no route points (they live in member-gated
    // Cloud Storage and are never fetched here), so top speed is unavailable and
    // its sentence is omitted — never rendered as "0 km/h".
    val shareText = driveShareText(drive, topSpeedMetersPerSecond = null)
    val shareUnavailable = stringResource(R.string.savedDrives_shareUnavailable)
    var showConfirm by remember { mutableStateOf(false) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { launchShareIntent(context, shareText, shareUnavailable) }) {
                    Text(text = stringResource(R.string.savedDrives_shareAction))
                }
                TextButton(onClick = { showConfirm = true }, enabled = !deleteInFlight) {
                    Text(text = stringResource(R.string.savedDrives_deleteAction))
                }
            }
        }
    }

    if (showConfirm) {
        DeleteDriveConfirmDialog(
            onConfirm = {
                showConfirm = false
                onDelete(drive.rideId)
            },
            onCancel = { showConfirm = false },
        )
    }
}

/** Saved-drive detail (Phase 12 slice 12): server-computed stats + share + delete. */
@Composable
fun SavedDriveDetailScreen(
    drive: SavedDrive,
    deleteStatus: DriveDeleteStatus,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Reads + decodes the drive's route.bin for the replay map; null in a
    // config-less / CI build (no Firebase), which degrades to the placeholder.
    routeRepository: RouteReplayRepository? = null,
    // Owner uid, needed for the member-gated rideRoutes/{uid}/{rideId} read.
    uid: String? = null,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Load the driven route once per opened drive; cached in memory by the
    // repository so re-opening the same drive redraws without a refetch. Loading
    // starts pending and resolves to Ready (points) or Unavailable — the reader
    // never throws.
    var routeState by remember(drive.rideId) {
        mutableStateOf<RouteReplayState>(RouteReplayState.Loading)
    }
    LaunchedEffect(drive.rideId, routeRepository, uid) {
        routeState =
            if (routeRepository != null && uid != null) {
                routeRepository.loadRoute(uid, drive.rideId)
            } else {
                RouteReplayState.Unavailable
            }
    }
    // A real Mapbox token is required to render the GL map; the config-less / CI
    // build has none and falls back to the placeholder card.
    val hasMapboxToken = stringResource(R.string.mapbox_access_token).isNotBlank()

    val dateText = formatDriveDate(drive.startedAtMillis ?: drive.createdAtMillis)
    val timeRangeText = formatDriveTimeRange(drive.startedAtMillis, drive.endedAtMillis)
    val averageSpeed =
        DriveFormatters.effectiveAverageSpeed(
            drive.averageSpeedMetersPerSecond,
            drive.distanceMeters,
            drive.durationSeconds,
        )

    // No loaded route points on the detail read model either, so the top-speed
    // sentence degrades away rather than showing a bogus figure.
    val shareSummary = driveShareText(drive, topSpeedMetersPerSecond = null)

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

            // Route replay: the actual driven route drawn on a static map. The
            // route points are read + decoded from member-gated Cloud Storage
            // (`rideRoutes/{uid}/{rideId}/route.bin`) by [routeRepository] and
            // rendered by [DriveRouteMap]. Every non-happy path degrades to a
            // one-line explanation instead of a broken/empty map.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.savedDrives_routeOverview),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val ready = routeState as? RouteReplayState.Ready
                    when {
                        // No token / no reader (config-less or CI build): the GL
                        // map cannot render, so explain rather than draw nothing.
                        !hasMapboxToken || routeRepository == null ->
                            RouteNote(stringResource(R.string.savedDrives_routeOverviewPlaceholder))

                        routeState is RouteReplayState.Loading ->
                            RouteNote(stringResource(R.string.savedDrives_routeLoading))

                        // A drawable route (≥ 2 points): draw it on the replay map.
                        ready != null && ready.points.size >= 2 -> {
                            // #504's top-speed sentence is a ONE-LINER away here
                            // once that PR lands on main:
                            //   DriveSummary.topSpeedMetersPerSecond(ready.points)
                            // is already implemented + tested; feed it these
                            // decoded points to render the share-text top speed.
                            DriveRouteMap(
                                points = ready.points,
                                modifier = Modifier.fillMaxWidth().height(ROUTE_MAP_HEIGHT),
                            )
                        }

                        // Ready but too few points to draw (a summary-only drive).
                        ready != null ->
                            RouteNote(stringResource(R.string.savedDrives_routeEmpty))

                        // Missing file / denied read / network / decode failure.
                        else ->
                            RouteNote(stringResource(R.string.savedDrives_routeUnavailable))
                    }
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
                onClick = { launchShareIntent(context, shareSummary, shareUnavailable) },
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
        DeleteDriveConfirmDialog(
            onConfirm = {
                showConfirm = false
                onDelete(drive.rideId)
            },
            onCancel = { showConfirm = false },
        )
    }
}

/** Fixed height for the embedded route replay map. */
private val ROUTE_MAP_HEIGHT = 240.dp

/** One-line muted explanation shown in place of the route map when it can't draw. */
@Composable
private fun RouteNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Builds a shareable, natural-language summary of a recorded drive, composed from
 * both locale strings (savedDrives_shareSummary + optional savedDrives_shareTopSpeed).
 *
 * @param topSpeedMetersPerSecond a client-side, GPS-glitch-filtered top speed
 *   ([DriveSummary.topSpeedMetersPerSecond]) when route points are loaded, else
 *   null. The extra sentence is appended only for a finite, non-negative value,
 *   so a route-less or summary-only drive omits it rather than claiming 0 km/h.
 */
@Composable
private fun driveShareText(
    drive: SavedDrive,
    topSpeedMetersPerSecond: Double?,
): String {
    val appName = stringResource(R.string.app_name)
    val distanceText = DriveFormatters.formatDistance(drive.distanceMeters)
    val durationText = DriveFormatters.formatDuration(drive.durationSeconds)
    val base =
        stringResource(R.string.savedDrives_shareSummary, appName, distanceText, durationText)
    val speed = topSpeedMetersPerSecond?.takeIf { it.isFinite() && it >= 0 }
    return if (speed != null) {
        base + " " +
            stringResource(R.string.savedDrives_shareTopSpeed, DriveFormatters.formatSpeed(speed))
    } else {
        base
    }
}

/**
 * Launches the system share sheet ([Intent.ACTION_SEND]) with [text]. Guards the
 * two ways the launch can throw: a non-Activity context needs
 * FLAG_ACTIVITY_NEW_TASK, and a device with no share target raises
 * ActivityNotFoundException (surfaced as a toast, not a crash).
 */
private fun launchShareIntent(context: Context, text: String, unavailableMessage: String) {
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    val chooser = Intent.createChooser(sendIntent, null)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
    }
}

/** Are-you-sure confirmation shared by the list row and the detail delete action. */
@Composable
private fun DeleteDriveConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.savedDrives_deleteConfirmTitle)) },
        text = { Text(stringResource(R.string.savedDrives_deleteConfirmBody)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.savedDrives_deleteConfirmAction))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.savedDrives_deleteConfirmCancel))
            }
        },
    )
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
