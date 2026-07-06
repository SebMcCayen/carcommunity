package com.kungsbackacarcommunity.app.drives

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * Saved-drives integration route (Phase 12 slice 12): observe the owner's
 * drives, drill into a selected drive's detail, and delete via the coordinator.
 * A successful delete returns to the list (the observer drops the row).
 */
@Composable
fun DrivesRoute(
    repository: DrivesRepository,
    uid: String,
    onBack: () -> Unit,
) {
    val state by
        remember(repository, uid) { repository.observeDrives(uid) }
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

    if (selectedRideId != null && selected != null) {
        SavedDriveDetailScreen(
            drive = selected,
            deleteStatus = deleteStatus,
            onDelete = { rideId -> scope.launch { coordinator.delete(rideId) } },
            onBack = {
                selectedRideId = null
                coordinator.reset()
            },
        )
    } else {
        DrivesListScreen(
            state = state,
            onSelect = { rideId -> selectedRideId = rideId },
            onBack = onBack,
        )
    }
}
