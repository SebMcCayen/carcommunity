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
) {
    // Bumped by the "try again" affordance to re-subscribe the observe flow.
    var reloadKey by rememberSaveable { mutableStateOf(0) }
    val state by
        remember(repository, uid, reloadKey) { repository.observeDrives(uid) }
            .collectAsState(initial = DrivesState.Loading)
    val coordinator = remember(repository) { DrivesCoordinator(repository) }
    val deleteStatus by coordinator.deleteStatus.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedRideId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deleteStatus) {
        if (deleteStatus == DriveDeleteStatus.Deleted) {
            selectedRideId = null
            coordinator.reset()
        }
    }

    val selected =
        (state as? DrivesState.Loaded)?.drives?.firstOrNull { it.rideId == selectedRideId }

    // System/gesture Back unwinds one internal level (detail -> list); at the
    // list root it is disabled so the shell's BackHandler returns to Home.
    // Enabling on `selectedRideId != null` alone (not also `selected != null`)
    // ensures a transient null `selected` during a list refresh still unwinds to
    // the list instead of falling through to the shell handler (Home).
    BackHandler(enabled = selectedRideId != null) {
        selectedRideId = null
        coordinator.reset()
    }

    when {
        selectedRideId != null && selected != null ->
            SavedDriveDetailScreen(
                drive = selected,
                deleteStatus = deleteStatus,
                onDelete = { rideId -> scope.launch { coordinator.delete(rideId) } },
                onBack = {
                    selectedRideId = null
                    coordinator.reset()
                },
            )

        else ->
            DrivesListScreen(
                state = state,
                onSelect = { rideId -> selectedRideId = rideId },
                onRetry = { reloadKey++ },
            )
    }
}
