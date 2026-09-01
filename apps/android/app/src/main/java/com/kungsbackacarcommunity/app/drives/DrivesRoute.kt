package com.kungsbackacarcommunity.app.drives

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.diagnostics.ClientErrorReporter
import com.kungsbackacarcommunity.app.diagnostics.rememberClientErrorReporter
import com.kungsbackacarcommunity.app.shell.LocalAeroBackAvailable
import com.kungsbackacarcommunity.app.subscription.EffectiveSubscriptionTier
import kotlinx.coroutines.launch

/**
 * Remembers the Firebase-backed route reader for the replay map, or null in a
 * config-less / CI build (no Firebase). Mirrors [rememberClientErrorReporter] so
 * the default can be overridden in tests.
 */
@Composable
private fun rememberRouteReplayRepository(): RouteReplayRepository? {
    val context = LocalContext.current
    return remember(context) { FirebaseRouteReplayRepository.createIfAvailable(context) }
}

/**
 * Saved-drives integration route (slice B1: server-authoritative reads). The
 * History list and the personal Statistics page now come from the tier-gated
 * `drives-listHistory` / `drives-stats` callables (via [historyRepository]); the
 * per-drive delete still goes through the `drives-delete` callable on
 * [repository]. Drives are recorded and saved at the end of a live-sharing
 * session, so this read-only history has no manual record affordance.
 *
 * Route replay (the detail map) intentionally keeps its current direct-Storage
 * path — its migration is slice B2 — and is untouched here.
 *
 * @param subscriptionTier the viewer's effective tier; a change reloads both the
 *   history list and the stats aggregate, so a downgrade drops now-hidden drives
 *   and an upgrade reveals more.
 */
@Composable
fun DrivesRoute(
    repository: DrivesRepository,
    historyRepository: DriveHistoryRepository,
    uid: String,
    subscriptionTier: EffectiveSubscriptionTier = EffectiveSubscriptionTier.COMMUNITY,
    errorReporter: ClientErrorReporter? = rememberClientErrorReporter(),
    // Reader for the driven-route replay map. Defaulted from Firebase (null in a
    // config-less / CI build) so callers and tests need not supply it.
    routeRepository: RouteReplayRepository? = rememberRouteReplayRepository(),
    // A ride to open straight to its detail on entry — set by the auto-keep
    // "Drive saved" dialog's History action (#856). Consumed via
    // [onInitialRideConsumed] so returning to the list and re-entering History
    // does not re-open it.
    initialRideId: String? = null,
    onInitialRideConsumed: () -> Unit = {},
) {
    val historyCoordinator = remember(historyRepository) { DriveHistoryCoordinator(historyRepository) }
    val statsCoordinator = remember(historyRepository) { DriveStatsCoordinator(historyRepository) }
    val state by historyCoordinator.state.collectAsState()

    // Bumped by the "try again" affordance to re-run the first-page load.
    var reloadKey by rememberSaveable { mutableStateOf(0) }
    // First load + retry + tier-change invalidation all route through reload():
    // a downgrade drops now-hidden drives, an upgrade reveals more.
    LaunchedEffect(subscriptionTier, reloadKey) {
        historyCoordinator.reload()
    }

    // Auto-file a GENUINE load failure (never the empty list). Keyed so it fires
    // once per entry into the Error state and once per retry, not per
    // recomposition; the backend dedups across users on top of that.
    val loadError = state as? DriveHistoryListState.Error
    LaunchedEffect(loadError != null, reloadKey, subscriptionTier) {
        if (loadError != null) {
            errorReporter?.report(
                feature = FEATURE_DRIVES_LIST,
                message = "Saved-drives history callable failed to load",
                code = loadError.code,
            )
        }
    }

    val coordinator = remember(repository) { DrivesCoordinator(repository) }
    val deleteStatus by coordinator.deleteStatus.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedRideId by remember { mutableStateOf<String?>(null) }
    // Deep-link into a specific drive's detail once, when the shell hands one in
    // (the "Drive saved" dialog's History action, #856). Keyed on the id so a new
    // request re-opens; consumed immediately so it fires exactly once and a manual
    // back-out to the list is not overridden on the next recomposition.
    LaunchedEffect(initialRideId) {
        if (initialRideId != null) {
            selectedRideId = initialRideId
            onInitialRideConsumed()
        }
    }
    // The "your driving" stats page is an internal level of this route (peer of
    // the detail view). It is server-authoritative now — its own callable, not a
    // fold over the list — so it is loaded on open and reloaded on tier change.
    var showStats by remember { mutableStateOf(false) }
    var statsReloadKey by remember { mutableStateOf(0) }
    val statsState by statsCoordinator.state.collectAsState()
    LaunchedEffect(showStats, subscriptionTier, statsReloadKey) {
        if (showStats) {
            statsCoordinator.load(
                monthStartMillis = DrivePeriodBoundaries.startOfCurrentMonthMillis(),
                monthEndMillis = DrivePeriodBoundaries.startOfNextMonthMillis(),
            )
        }
    }

    // Scroll position of the History list, hoisted here so it OUTLIVES the drill-in
    // levels (detail/stats swap DrivesListScreen out of the composition — see #996).
    val historyListState = rememberLazyListState()

    val loaded = state as? DriveHistoryListState.Loaded

    LaunchedEffect(deleteStatus) {
        if (deleteStatus == DriveDeleteStatus.Deleted) {
            selectedRideId = null
            coordinator.reset()
            // Reload the history: deleting a visible drive can promote a
            // previously-hidden one into the tier window (e.g. Community's 6th
            // drive becomes visible once one of the newest 5 is removed), and the
            // hidden-count banner must re-derive. This replaces the old snapshot
            // listener that reconciled the removal automatically.
            historyCoordinator.reload()
        }
    }

    val selected = loaded?.drives?.firstOrNull { it.rideId == selectedRideId }

    // System/gesture Back unwinds one internal level (detail/stats -> list); at
    // the list root it is disabled so the shell's BackHandler returns to Home.
    BackHandler(enabled = selectedRideId != null || showStats) {
        selectedRideId = null
        showStats = false
        coordinator.reset()
    }

    val level =
        drivesLevel(
            hasSelectedDrive = selectedRideId != null && selected != null,
            showStats = showStats,
        )
    when (level) {
        DrivesLevel.DETAIL ->
            // Drill-in level: provide the pinned in-app Back arrow (#807) around
            // the AeroPage this screen renders, so gesture-nav users can return to
            // the History list.
            CompositionLocalProvider(LocalAeroBackAvailable provides true) {
                // `selected` is non-null on this branch (see [drivesLevel]); the
                // null-check re-establishes the smart cast for the compiler.
                selected?.let { drive ->
                    SavedDriveDetailScreen(
                        drive = drive,
                        deleteStatus = deleteStatus,
                        onDelete = { rideId -> scope.launch { coordinator.delete(rideId) } },
                        onBack = {
                            selectedRideId = null
                            coordinator.reset()
                        },
                        routeRepository = routeRepository,
                        uid = uid,
                    )
                }
            }

        DrivesLevel.STATS ->
            // Drill-in level: provide the pinned in-app Back arrow (#807, #844) so
            // the Statistics page has a visible way back to the History list.
            CompositionLocalProvider(LocalAeroBackAvailable provides true) {
                DriveStatsScreen(
                    state = statsState,
                    onRetry = { statsReloadKey++ },
                )
            }

        DrivesLevel.LIST ->
            DrivesListScreen(
                state = state,
                onSelect = { rideId -> selectedRideId = rideId },
                onDelete = { rideId -> scope.launch { coordinator.delete(rideId) } },
                deleteStatus = deleteStatus,
                onRetry = { reloadKey++ },
                onShowStats = { showStats = true },
                onLoadMore = { scope.launch { historyCoordinator.loadMore() } },
                listState = historyListState,
            )
    }
}

/** The mutually-exclusive internal levels [DrivesRoute] can render, in priority order. */
internal enum class DrivesLevel { DETAIL, STATS, LIST }

/**
 * Pure routing decision for [DrivesRoute]. Detail needs the selected drive to
 * still resolve ([hasSelectedDrive]) — during a list reload/resubscribe a
 * transient null selection falls back to [DrivesLevel.LIST] so the list can render
 * the real loading/error state. Statistics is now server-authoritative (its own
 * callable, not a fold over the list), so it no longer depends on the list being
 * loaded and stays open across a history reload.
 */
internal fun drivesLevel(hasSelectedDrive: Boolean, showStats: Boolean): DrivesLevel =
    when {
        hasSelectedDrive -> DrivesLevel.DETAIL
        showStats -> DrivesLevel.STATS
        else -> DrivesLevel.LIST
    }

/** Stable feature key for the saved-drives list (a backend fingerprint input). */
private const val FEATURE_DRIVES_LIST = "drives.list"
