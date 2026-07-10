package com.kungsbackacarcommunity.app.drives

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Saved-drives integration route (Phase 12 slice 12): observe the owner's
 * drives, drill into a selected drive's detail, delete via the coordinator, and
 * record a new drive (write side). A successful delete returns to the list.
 *
 * Recording is gated on [isActiveMember] (the `drives-save` callable is
 * member-gated backend-side too). Each recording session gets a fresh
 * sourceSessionId UUID so retries are idempotent per recording.
 */
@Composable
fun DrivesRoute(
    repository: DrivesRepository,
    uid: String,
    isActiveMember: Boolean,
    onBack: () -> Unit,
) {
    val state by
        remember(repository, uid) { repository.observeDrives(uid) }
            .collectAsState(initial = DrivesState.Loading)
    val coordinator = remember(repository) { DrivesCoordinator(repository) }
    val deleteStatus by coordinator.deleteStatus.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedRideId by remember { mutableStateOf<String?>(null) }
    // Bumped each time recording is (re)entered so a new coordinator + fresh
    // sourceSessionId is minted per recording session.
    var recordSession by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(deleteStatus) {
        if (deleteStatus == DriveDeleteStatus.Deleted) {
            selectedRideId = null
            coordinator.reset()
        }
    }

    val selected =
        (state as? DrivesState.Loaded)?.drives?.firstOrNull { it.rideId == selectedRideId }

    // System/gesture Back unwinds one internal level (recorder or detail -> list);
    // at the list root it is disabled so the shell's BackHandler returns to Home.
    BackHandler(enabled = recordSession != null || (selectedRideId != null && selected != null)) {
        when {
            recordSession != null -> recordSession = null
            else -> {
                selectedRideId = null
                coordinator.reset()
            }
        }
    }

    when {
        recordSession != null -> {
            val recordingCoordinator =
                remember(repository, recordSession) {
                    DriveRecordingCoordinator(repository, UUID.randomUUID().toString())
                }
            RecordDriveScreen(
                coordinator = recordingCoordinator,
                isActiveMember = isActiveMember,
                onBack = { recordSession = null },
            )
        }

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
                onRecord = { recordSession = (recordSession ?: 0) + 1 },
                onBack = onBack,
            )
    }
}
