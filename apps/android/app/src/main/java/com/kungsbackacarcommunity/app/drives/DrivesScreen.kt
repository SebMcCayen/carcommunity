package com.kungsbackacarcommunity.app.drives

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.map.DriveRouteMap
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding
import java.text.DateFormat
import java.util.Calendar
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
    // Search/filter/sort state for the History list. Survives config changes
    // (rememberSaveable): the query is a String and the three enums are
    // Serializable, so the default autoSaver handles them. This is a pure UI
    // concern of the list surface, so it is owned here rather than hoisted into
    // DrivesRoute.
    var query by rememberSaveable { mutableStateOf("") }
    var dateRange by rememberSaveable { mutableStateOf(DriveDateRange.ALL) }
    var distanceBand by rememberSaveable { mutableStateOf(DriveDistanceBand.ALL) }
    var sort by rememberSaveable { mutableStateOf(DriveSort.NEWEST) }
    val criteria = DriveFilterCriteria(query, dateRange, distanceBand, sort)

    // Resolve the period presets to epoch-millis boundaries at the composable
    // edge (a Calendar/time-zone concern) so the fold in [DriveFilters] stays
    // pure and deterministic. Recomputed each composition — cheap, and it lets a
    // week/month rollover correct itself on the next recomposition rather than
    // pinning to the boundary the screen opened in (mirrors [DriveStatsScreen]).
    val weekStartMillis = startOfCurrentWeekMillis()
    val monthStartMillis = startOfCurrentMonthMillis()

    // Filter over the FULL loaded list (an owner query with no limit — see
    // [DriveFilters]), so results are complete, never a partial page. Computed
    // unconditionally (empty in for a non-Loaded state) so the remember slot is
    // stable across the Loading -> Loaded transition.
    val allDrives = (state as? DrivesState.Loaded)?.drives ?: emptyList()
    val filteredDrives =
        remember(allDrives, criteria, weekStartMillis, monthStartMillis) {
            DriveFilters.filterDrives(allDrives, criteria, weekStartMillis, monthStartMillis)
        }

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
                        // The stats entry reflects ALL drives (all-time), never the
                        // filtered set: "your driving" is a lifetime figure and would
                        // be confusing if it changed as you typed a search. It is
                        // shown whenever any drive exists, independent of the filter.
                        if (onShowStats != null) {
                            item { StatsEntryCard(onShowStats) }
                        }
                        item {
                            DriveFilterBar(
                                criteria = criteria,
                                onQueryChange = { query = it },
                                onDateRangeChange = { dateRange = it },
                                onDistanceBandChange = { distanceBand = it },
                                onSortChange = { sort = it },
                                onClear = {
                                    query = ""
                                    dateRange = DriveDateRange.ALL
                                    distanceBand = DriveDistanceBand.ALL
                                    sort = DriveSort.NEWEST
                                },
                            )
                        }
                        if (filteredDrives.isEmpty()) {
                            // Distinct from the "no saved drives yet" empty state:
                            // drives exist, they just don't match the active filters.
                            item {
                                NoMatchingDrives(
                                    onClear = {
                                        query = ""
                                        dateRange = DriveDateRange.ALL
                                        distanceBand = DriveDistanceBand.ALL
                                        sort = DriveSort.NEWEST
                                    },
                                )
                            }
                        } else {
                            items(filteredDrives, key = { it.rideId }) { drive ->
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

/**
 * Shown in place of the drive rows when at least one drive exists but none match
 * the active filters. Distinct from [EmptyDrives] ("no saved drives yet") and
 * carries a clear-filters affordance so the user is never stranded on an empty
 * result they can't undo.
 */
@Composable
private fun NoMatchingDrives(onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = stringResource(R.string.savedDrives_filterNoMatches),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onClear) {
                Text(text = stringResource(R.string.savedDrives_filterNoMatchesAction))
            }
        }
    }
}

/**
 * Search field + filter/sort chips at the top of the History list. Pure
 * presentation over a [DriveFilterCriteria]; all matching lives in
 * [DriveFilters]. Selecting an already-selected period or distance chip toggles
 * it back off (to ALL); sort is single-select and always has exactly one active.
 */
@Composable
private fun DriveFilterBar(
    criteria: DriveFilterCriteria,
    onQueryChange: (String) -> Unit,
    onDateRangeChange: (DriveDateRange) -> Unit,
    onDistanceBandChange: (DriveDistanceBand) -> Unit,
    onSortChange: (DriveSort) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        OutlinedTextField(
            value = criteria.query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.savedDrives_filterSearchLabel)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
        )

        ChipSection(stringResource(R.string.savedDrives_filterPeriod)) {
            FilterChip(
                selected = criteria.dateRange == DriveDateRange.THIS_WEEK,
                onClick = {
                    onDateRangeChange(
                        toggledRange(criteria.dateRange, DriveDateRange.THIS_WEEK),
                    )
                },
                label = { Text(stringResource(R.string.savedDrives_filterThisWeek)) },
            )
            FilterChip(
                selected = criteria.dateRange == DriveDateRange.THIS_MONTH,
                onClick = {
                    onDateRangeChange(
                        toggledRange(criteria.dateRange, DriveDateRange.THIS_MONTH),
                    )
                },
                label = { Text(stringResource(R.string.savedDrives_filterThisMonth)) },
            )
        }

        ChipSection(stringResource(R.string.savedDrives_filterDistance)) {
            FilterChip(
                selected = criteria.distanceBand == DriveDistanceBand.UNDER_10_KM,
                onClick = {
                    onDistanceBandChange(
                        toggledBand(criteria.distanceBand, DriveDistanceBand.UNDER_10_KM),
                    )
                },
                label = { Text(stringResource(R.string.savedDrives_filterUnder10)) },
            )
            FilterChip(
                selected = criteria.distanceBand == DriveDistanceBand.FROM_10_TO_50_KM,
                onClick = {
                    onDistanceBandChange(
                        toggledBand(criteria.distanceBand, DriveDistanceBand.FROM_10_TO_50_KM),
                    )
                },
                label = { Text(stringResource(R.string.savedDrives_filter10to50)) },
            )
            FilterChip(
                selected = criteria.distanceBand == DriveDistanceBand.OVER_50_KM,
                onClick = {
                    onDistanceBandChange(
                        toggledBand(criteria.distanceBand, DriveDistanceBand.OVER_50_KM),
                    )
                },
                label = { Text(stringResource(R.string.savedDrives_filterOver50)) },
            )
        }

        ChipSection(stringResource(R.string.savedDrives_filterSort)) {
            FilterChip(
                selected = criteria.sort == DriveSort.NEWEST,
                onClick = { onSortChange(DriveSort.NEWEST) },
                label = { Text(stringResource(R.string.savedDrives_sortNewest)) },
            )
            FilterChip(
                selected = criteria.sort == DriveSort.LONGEST,
                onClick = { onSortChange(DriveSort.LONGEST) },
                label = { Text(stringResource(R.string.savedDrives_sortLongest)) },
            )
            FilterChip(
                selected = criteria.sort == DriveSort.FASTEST_AVERAGE,
                onClick = { onSortChange(DriveSort.FASTEST_AVERAGE) },
                label = { Text(stringResource(R.string.savedDrives_sortFastest)) },
            )
        }

        // Only offered when something is actually filtering the list (sort alone
        // never hides a drive, so it doesn't count — see hasActiveFilters).
        if (criteria.hasActiveFilters) {
            TextButton(onClick = onClear) {
                Text(text = stringResource(R.string.savedDrives_filterClear))
            }
        }
    }
}

/** A labelled, horizontally-scrollable row of filter chips. */
@Composable
private fun ChipSection(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            chips()
        }
    }
}

/** Clicking the active period chip returns to ALL; otherwise selects it. */
private fun toggledRange(current: DriveDateRange, target: DriveDateRange): DriveDateRange =
    if (current == target) DriveDateRange.ALL else target

/** Clicking the active distance chip returns to ALL; otherwise selects it. */
private fun toggledBand(current: DriveDistanceBand, target: DriveDistanceBand): DriveDistanceBand =
    if (current == target) DriveDistanceBand.ALL else target

/** Start of the current calendar month (local time zone) as epoch-millis. */
private fun startOfCurrentMonthMillis(): Long =
    Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * Start of the current week (local time zone, honouring the locale's first day
 * of week) as epoch-millis. Truncates to midnight, then steps back to the
 * week's first day so it is correct regardless of today's position in the week.
 */
private fun startOfCurrentWeekMillis(): Long =
    Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        val delta = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
        add(Calendar.DAY_OF_YEAR, -delta)
    }.timeInMillis

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

    // A real Mapbox token is required to render the GL map; the config-less / CI
    // build has none and falls back to the placeholder card.
    val hasMapboxToken = stringResource(R.string.mapbox_access_token).isNotBlank()

    // Load the driven route once per opened drive; cached in memory by the
    // repository so re-opening the same drive redraws without a refetch. Loading
    // starts pending and resolves to Ready (points) or Unavailable — the reader
    // never throws. Gated on [hasMapboxToken] too: with no token the decoded
    // route can never be drawn, so skip the Storage read + decode entirely.
    var routeState by remember(drive.rideId) {
        mutableStateOf<RouteReplayState>(RouteReplayState.Loading)
    }
    LaunchedEffect(drive.rideId, routeRepository, uid, hasMapboxToken) {
        routeState =
            if (routeRepository != null && uid != null && hasMapboxToken) {
                routeRepository.loadRoute(uid, drive.rideId)
            } else {
                RouteReplayState.Unavailable
            }
    }

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
                    modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
                    verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
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
                    modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
                    verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
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
