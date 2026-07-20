package com.kungsbackacarcommunity.app.drives

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kungsbackacarcommunity.app.diagnostics.ClientErrorReporter
import com.kungsbackacarcommunity.app.diagnostics.rememberClientErrorReporter
import kotlinx.coroutines.launch

/**
 * Saved-drives integration route (Phase 12 slice 12): observe the owner's
 * drives, drill into a selected drive's detail, and delete via the coordinator.
 * A successful delete returns to the list. Drives are recorded and saved at the
 * end of a live-sharing session, so this read-only history has no manual record
 * affordance.
 */
@Composable
fun DrivesRoute(
    repository: DrivesRepository,
    uid: String,
    errorReporter: ClientErrorReporter? = rememberClientErrorReporter(),
) {
    // Bumped by the "try again" affordance to re-subscribe the observe flow.
    var reloadKey by rememberSaveable { mutableStateOf(0) }
    val state by
        remember(repository, uid, reloadKey) { repository.observeDrives(uid) }
            .collectAsState(initial = DrivesState.Loading)

    // Auto-file a GENUINE load failure (never the empty list). Keyed so it fires
    // once per entry into the Error state and once per retry, not per
    // recomposition; the backend dedups across users on top of that.
    val loadError = state as? DrivesState.Error
    LaunchedEffect(loadError != null, reloadKey) {
        if (loadError != null) {
            errorReporter?.report(
                feature = FEATURE_DRIVES_LIST,
                message = "Saved-drives owner query failed to load",
                code = loadError.code,
            )
        }
    }
    val coordinator = remember(repository) { DrivesCoordinator(repository) }
    val deleteStatus by coordinator.deleteStatus.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedRideId by remember { mutableStateOf<String?>(null) }
    // The "your driving" stats page is an internal level of this route (peer of
    // the detail view), folded over the already-loaded drive list — no refetch.
    var showStats by remember { mutableStateOf(false) }

    val loaded = state as? DrivesState.Loaded

    LaunchedEffect(deleteStatus) {
        if (deleteStatus == DriveDeleteStatus.Deleted) {
            selectedRideId = null
            coordinator.reset()
        }
    }

    // The stats level is folded over the loaded list, so it is only valid while
    // the drives are Loaded. If they leave Loaded WHILE stats is open (a
    // transient Firestore listener error, a retry/resubscribe), permanently exit
    // the level back to the list — the list renders the real loading/error state,
    // whereas an empty-drives fold would read as a misleading "no drives" page.
    LaunchedEffect(showStats, loaded == null) {
        if (showStats && loaded == null) showStats = false
    }

    val selected = loaded?.drives?.firstOrNull { it.rideId == selectedRideId }

    // System/gesture Back unwinds one internal level (detail/stats -> list); at
    // the list root it is disabled so the shell's BackHandler returns to Home.
    // Enabling on `selectedRideId != null` alone (not also `selected != null`)
    // ensures a transient null `selected` during a list refresh still unwinds to
    // the list instead of falling through to the shell handler (Home).
    BackHandler(enabled = selectedRideId != null || showStats) {
        selectedRideId = null
        showStats = false
        coordinator.reset()
    }

    val level =
        drivesLevel(
            hasSelectedDrive = selectedRideId != null && selected != null,
            showStats = showStats,
            isLoaded = loaded != null,
        )
    when (level) {
        DrivesLevel.DETAIL ->
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
                )
            }

        DrivesLevel.STATS ->
            // `loaded` is non-null on this branch (see [drivesLevel]); never fed
            // an empty fold when the drives have left the Loaded state.
            loaded?.let { DriveStatsScreen(drives = it.drives) }

        DrivesLevel.LIST ->
            DrivesListScreen(
                state = state,
                onSelect = { rideId -> selectedRideId = rideId },
                onRetry = { reloadKey++ },
                onShowStats = { showStats = true },
            )
    }
}

/** The mutually-exclusive internal levels [DrivesRoute] can render, in priority order. */
internal enum class DrivesLevel { DETAIL, STATS, LIST }

/**
 * Pure routing decision for [DrivesRoute]. Both drill-in levels are only valid
 * while their backing data is present: detail needs the selected drive to still
 * resolve ([hasSelectedDrive]), and stats needs the drive list to still be
 * Loaded ([isLoaded]). When either backing datum drops out (a transient listener
 * error, a retry/resubscribe), this falls back to [DrivesLevel.LIST] so the list
 * screen can render the real loading/error state instead of a stale or empty
 * drill-in view.
 */
internal fun drivesLevel(hasSelectedDrive: Boolean, showStats: Boolean, isLoaded: Boolean): DrivesLevel =
    when {
        hasSelectedDrive -> DrivesLevel.DETAIL
        showStats && isLoaded -> DrivesLevel.STATS
        else -> DrivesLevel.LIST
    }

/** Stable feature key for the saved-drives list (a backend fingerprint input). */
private const val FEATURE_DRIVES_LIST = "drives.list"
