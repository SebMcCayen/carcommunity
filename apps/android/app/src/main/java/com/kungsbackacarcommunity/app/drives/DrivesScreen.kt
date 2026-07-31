package com.kungsbackacarcommunity.app.drives

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.map.DriveRouteFullscreenDialog
import com.kungsbackacarcommunity.app.map.DriveRouteMap
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding
import java.text.DateFormat
import java.util.Date

/**
 * Test tag on the show/hide control for the History filter section. `internal`,
 * matching the other test-only tags in this module — the instrumented source set
 * still sees it, and nothing outside the module has any business tagging on it.
 */
internal const val DRIVE_FILTER_TOGGLE_TAG = "drives_filter_toggle"

/**
 * Test tag on the small "your driving stats" action in the History header. This
 * compact icon button replaced the full-width stats card that used to sit between
 * the header and the filter section, so the stats page stays reachable without
 * the large window. `internal` for the same reason as [DRIVE_FILTER_TOGGLE_TAG].
 */
internal const val DRIVE_STATS_ENTRY_TAG = "drives_stats_entry"

/**
 * Expand/collapse duration for the History filter section, matching the shell's
 * search-bar transition so the two read as the same app.
 */
private const val FILTER_TRANSITION_MILLIS = 200

/** Material's minimum touch-target edge; the filter header row is shorter without it. */
private val MIN_TOUCH_TARGET = 48.dp

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

    // The filter controls are COLLAPSED by default so the drives themselves are
    // the first thing on the page; the header badge (see [DriveFilterSection])
    // keeps any active filter visible while collapsed. rememberSaveable so a
    // rotation mid-filtering doesn't slam the section shut under the user — and
    // note the filter values above are saved independently of it, so collapsing
    // never resets a filter.
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }

    // One reset shared by the filter bar's "clear filters" button and the
    // no-matches empty state, so the two can't drift apart. Resets sort too:
    // "clear" means "put the list back the way it was", and a stale FASTEST sort
    // left behind after clearing would be a surprise.
    val clearFilters: () -> Unit = {
        query = ""
        dateRange = DriveDateRange.ALL
        distanceBand = DriveDistanceBand.ALL
        sort = DriveSort.NEWEST
    }

    // Resolve the period presets to epoch-millis boundaries at the composable
    // edge (a Calendar/time-zone concern) so the fold in [DriveFilters] stays
    // pure and deterministic. Recomputed each composition — cheap, and it lets a
    // week/month rollover correct itself on the next recomposition rather than
    // pinning to the boundary the screen opened in (mirrors [DriveStatsScreen]).
    val weekStartMillis = DrivePeriodBoundaries.startOfCurrentWeekMillis()
    val monthStartMillis = DrivePeriodBoundaries.startOfCurrentMonthMillis()

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
                // The personal "your driving" stats page is reached from a small,
                // unobtrusive action beside the title (see [DrivesHeader]) rather
                // than the full-width card that used to sit between the header and
                // the filters. The action is offered only once at least one drive
                // is loaded, so it never opens a page of zeroes — the same "shown
                // only when a drive exists" rule the old card carried.
                val statsEntry =
                    onShowStats?.takeIf {
                        (state as? DrivesState.Loaded)?.drives?.isNotEmpty() == true
                    }
                DrivesHeader(onShowStats = statsEntry)
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
                        item {
                            DriveFilterSection(
                                criteria = criteria,
                                expanded = filtersExpanded,
                                onToggleExpanded = { filtersExpanded = !filtersExpanded },
                                onQueryChange = { query = it },
                                onDateRangeChange = { dateRange = it },
                                onDistanceBandChange = { distanceBand = it },
                                onSortChange = { sort = it },
                                onClear = clearFilters,
                            )
                        }
                        if (filteredDrives.isEmpty()) {
                            // Distinct from the "no saved drives yet" empty state:
                            // drives exist, they just don't match the active filters.
                            item { NoMatchingDrives(onClear = clearFilters) }
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

/**
 * History page header: the title, plus a small "your driving stats" action when
 * [onShowStats] is non-null. Keeping the action in the header (rather than a
 * full-width card below it) is what lets the stats page stay reachable without
 * the large window that used to sit between the header and the filters.
 */
@Composable
private fun DrivesHeader(onShowStats: (() -> Unit)?) {
    val title = stringResource(R.string.savedDrives_screenTitle)
    if (onShowStats == null) {
        AeroPageTitle(title)
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            AeroPageTitle(title, modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = onShowStats,
                modifier = Modifier.testTag(DRIVE_STATS_ENTRY_TAG),
            ) {
                Icon(
                    imageVector = Icons.Filled.BarChart,
                    contentDescription = stringResource(R.string.savedDrives_statsEntryAction),
                )
            }
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
 * The History filter controls, collapsed behind a one-tap header.
 *
 * Expanded, the search field plus three chip rows are several hundred dp tall —
 * on a phone that pushed the actual drives below the fold, which is the wrong
 * default for a page whose job is to show your drives. So the body is collapsed
 * by default and the header stands in for it.
 *
 * The header carries a COUNT BADGE rather than a summary chip row: the whole
 * point of collapsing is to reclaim vertical space, and echoing the active
 * filters as chips would give most of it straight back (and reflow the header to
 * two lines once two filters are on, so the drives below would jump as you
 * filter). A badge is a fixed one-line height whatever is selected, and the
 * number answers the only question the collapsed state has to answer — "is
 * something hiding drives from me, and how much do I have to undo?". The exact
 * selections are one tap away, and the no-matches state still offers its own
 * clear-filters escape hatch, so nothing is unreachable while collapsed.
 *
 * @param expanded whether the body is showing; hoisted so it survives rotation.
 */
@Composable
private fun DriveFilterSection(
    criteria: DriveFilterCriteria,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onQueryChange: (String) -> Unit,
    onDateRangeChange: (DriveDateRange) -> Unit,
    onDistanceBandChange: (DriveDistanceBand) -> Unit,
    onSortChange: (DriveSort) -> Unit,
    onClear: () -> Unit,
) {
    // `spacedBy` costs nothing while collapsed. AnimatedVisibility gates its whole
    // emission on `visible(currentState) || visible(targetState) || isSeeking ||
    // hasInitialValueAnimations`, so once the transition has SETTLED on
    // `visible = false` it composes nothing at all — no layout node, rather than a
    // zero-height one. The Column therefore measures a single child, and
    // arrangement spacing only ever goes BETWEEN children, so nothing is left
    // hanging under the collapsed header. (Re-check that guard, not this comment,
    // if a Compose upgrade ever makes the collapsed header look bottom-heavy.)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        DriveFilterToggle(
            expanded = expanded,
            activeFilterCount = criteria.activeFilterCount,
            onToggle = onToggleExpanded,
        )
        // Height + fade rather than a bare `if`, so the drives below slide out of
        // the way instead of teleporting. Inside a LazyColumn item this is safe:
        // the item simply re-measures each frame as the body grows/shrinks.
        AnimatedVisibility(
            visible = expanded,
            enter =
                expandVertically(animationSpec = tween(FILTER_TRANSITION_MILLIS, easing = FastOutSlowInEasing)) +
                    fadeIn(animationSpec = tween(FILTER_TRANSITION_MILLIS, easing = FastOutSlowInEasing)),
            exit =
                shrinkVertically(animationSpec = tween(FILTER_TRANSITION_MILLIS, easing = FastOutSlowInEasing)) +
                    fadeOut(animationSpec = tween(FILTER_TRANSITION_MILLIS, easing = FastOutSlowInEasing)),
        ) {
            DriveFilterBar(
                criteria = criteria,
                onQueryChange = onQueryChange,
                onDateRangeChange = onDateRangeChange,
                onDistanceBandChange = onDistanceBandChange,
                onSortChange = onSortChange,
                onClear = onClear,
            )
        }
    }
}

/**
 * The show/hide control for [DriveFilterSection]: a full-width row carrying a
 * filter icon, the "Filters" label, the active-filter count badge and a chevron.
 *
 * The whole row is the tap target (min 48 dp tall, so it clears the Material
 * touch-target minimum even though the icon and text are shorter than that) and
 * it exposes ONE merged accessibility node describing both the action and the
 * badge — the icons and the badge deliberately carry no semantics of their own so
 * TalkBack reads the control out exactly once, count included.
 *
 * `internal` rather than private only so the instrumented `DriveFilterToggleTest`
 * can drive that accessibility contract directly instead of asserting it through
 * the whole History screen.
 */
@Composable
internal fun DriveFilterToggle(
    expanded: Boolean,
    activeFilterCount: Int,
    onToggle: () -> Unit,
) {
    val actionLabel =
        stringResource(
            if (expanded) {
                R.string.savedDrives_filterToggleCollapse
            } else {
                R.string.savedDrives_filterToggleExpand
            },
        )
    // Spoken form of the badge. Phrased "Active filters: 2" rather than
    // "2 active filters" so it stays grammatical at every count — the generated
    // string resources have no <plurals> support, and Swedish "1 aktiva filter"
    // would be wrong.
    val activeLabel =
        if (activeFilterCount > 0) {
            stringResource(R.string.savedDrives_filterActiveCount, activeFilterCount)
        } else {
            null
        }
    // "Show filters, Active filters: 2" — the count is part of the spoken label,
    // because a badge that is only a visual cue would strand a TalkBack user with
    // exactly the problem collapsing created: a short list and no stated reason.
    val description = if (activeLabel != null) "$actionLabel, $activeLabel" else actionLabel

    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH_TARGET)
                .testTag(DRIVE_FILTER_TOGGLE_TAG)
                .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = null,
                modifier = Modifier.size(KccSpacing.s5),
            )
            Text(
                text = stringResource(R.string.savedDrives_filterToggle),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (activeFilterCount > 0) {
                // Bare digit: the surrounding "Filters" label already says what is
                // being counted, and the merged contentDescription carries the
                // spoken sentence, so the pill stays a fixed, narrow width.
                ActiveFilterBadge(activeFilterCount.toString())
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(KccSpacing.s5),
            )
        }
    }
}

/**
 * The count pill on the filter header. Theme colours only (primary /
 * onPrimary), so it stays legible in both light and dark without a hardcoded
 * pair.
 *
 * Carries no semantics of its own, and the digit is CLEARED rather than merely
 * left unlabelled: [DriveFilterToggle] merges its descendants and already speaks
 * the count in its own `contentDescription`, so the pill is a purely visual echo
 * of something the header states. Leaving the `Text` in the tree would put the
 * number into the merged node twice over.
 */
@Composable
private fun ActiveFilterBadge(label: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier =
                Modifier
                    .padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s1)
                    .clearAndSetSemantics {},
        )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Drawn from the polyline already on the drive document — no
                // fetch, no map instance, no work per frame (RouteThumbnail).
                RouteThumbnailImage(encodedPolyline = drive.routeThumbnail)
                // weight(1f): the thumbnail is a fixed size, the text takes
                // whatever is left and wraps inside it — on a narrow phone the
                // stat line must reflow, never spill past the card edge.
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = driveTitle(drive),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Distance, duration and maximum speed in ONE line, one
                    // style, one colour, in that order. Max speed is a fact
                    // about the drive exactly like the other two and is
                    // rendered exactly like them — no emphasis, no colour that
                    // rewards a bigger number, nothing to compare it against.
                    // See SavedDrive.maxSpeedMetersPerSecond.
                    Text(
                        text =
                            DriveFormatters.formatDistance(drive.distanceMeters) +
                                " · " + DriveFormatters.formatDuration(drive.durationSeconds) +
                                " · " + stringResource(
                                    R.string.savedDrives_maxSpeedShort,
                                    // Null (no stored value) formats as the
                                    // missing-value dash, never as "0 km/h".
                                    DriveFormatters.formatSpeed(drive.maxSpeedMetersPerSecond),
                                ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    // Whether the full-screen, zoomable route popup is open. Reset per drive so a
    // new drive never opens with a stale popup showing the previous route.
    var showRouteMap by remember(drive.rideId) { mutableStateOf(false) }
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

    // Top speed for the share text is derived from the decoded route points once
    // the route.bin loads (the write side now uploads it and the reader decodes
    // it): DriveSummary applies the same GPS-glitch filter the backend uses for
    // distance, so a lone spike can't claim an absurd figure. Null while the
    // route is Loading/Unavailable or a summary-only drive, and the sentence
    // degrades away rather than showing a bogus 0.
    // Keyed on routeState so the fold (up to ~20k points) only runs when the
    // decoded route actually changes, not on every unrelated recomposition. The
    // RoutePoint overload folds straight over the decoded points, so this no
    // longer allocates a ~20k RecordedPoint list on the UI thread as the route
    // loads.
    val topSpeed =
        remember(routeState) {
            (routeState as? RouteReplayState.Ready)
                ?.points
                ?.let { DriveSummary.topSpeedMetersPerSecond(it) }
        }
    val shareSummary = driveShareText(drive, topSpeedMetersPerSecond = topSpeed)

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
                    // The same neutral row as every other stat — deliberately
                    // indistinguishable from average speed above it. Absent on
                    // drives saved before the field existed, which formatSpeed
                    // renders as the missing-value dash rather than "0 km/h".
                    StatRow(
                        stringResource(R.string.savedDrives_maxSpeed),
                        DriveFormatters.formatSpeed(drive.maxSpeedMetersPerSecond),
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
                        // The same decoded points also feed the share-text top
                        // speed (computed into [topSpeed] above). Tapping the
                        // thumbnail opens the full-screen, zoomable popup (with the
                        // per-km markers) — the expand badge makes that discoverable.
                        ready != null && ready.points.size >= 2 -> {
                            val expandLabel = stringResource(R.string.savedDrives_routeExpand)
                            // TalkBack label for the whole tap target: the child is
                            // an AndroidView-hosted MapView (a11y black box), so the
                            // clickable node needs its OWN contentDescription — the
                            // onClickLabel only names the action, not the control.
                            // mergeDescendants keeps it a single focusable element.
                            val thumbnailLabel =
                                stringResource(R.string.savedDrives_routeMapThumbnailLabel)
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(ROUTE_MAP_HEIGHT)
                                        .clickable(onClickLabel = expandLabel) {
                                            showRouteMap = true
                                        }
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = thumbnailLabel
                                        },
                            ) {
                                DriveRouteMap(
                                    points = ready.points,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                // Non-interactive expand affordance (the whole
                                // thumbnail is the tap target); a translucent badge
                                // so it reads over any basemap tile.
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    shape = CircleShape,
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(KccSpacing.s2),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Fullscreen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(KccSpacing.s1).size(24.dp),
                                    )
                                }
                            }
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

    // The full-screen, zoomable route popup. Only openable from the drawable-route
    // branch (which requires ≥ 2 points), so the points read here are always
    // drawable; guarded on the same Ready state so a route that becomes
    // unavailable can't leave an empty popup open.
    if (showRouteMap) {
        val ready = routeState as? RouteReplayState.Ready
        if (ready != null && ready.points.size >= 2) {
            DriveRouteFullscreenDialog(
                points = ready.points,
                onDismiss = { showRouteMap = false },
            )
        } else {
            showRouteMap = false
        }
    }
}

/** Fixed height for the embedded route replay map. */
private val ROUTE_MAP_HEIGHT = 240.dp

/**
 * One-line muted explanation shown in place of the route map when it can't draw.
 * Shared with the end-of-session summary's route section
 * ([SessionSummaryDialog]), so "no token" / "nothing recorded" reads the same
 * wherever a route fails to render.
 */
@Composable
internal fun RouteNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
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
