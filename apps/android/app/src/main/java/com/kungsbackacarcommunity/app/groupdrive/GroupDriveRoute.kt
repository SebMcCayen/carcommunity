package com.kungsbackacarcommunity.app.groupdrive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.RsvpStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Group-driving integration route (Phase 12 slice 11): wires the roster +
 * own-status flows and the coordinator into [GroupDriveScreen].
 */
@Composable
fun GroupDriveRoute(
    repository: GroupDriveRepository,
    coordinator: GroupDriveCoordinator?,
    eventId: String,
    uid: String,
    isActiveMember: Boolean,
    eventStatus: EventStatus?,
    myRsvp: RsvpStatus?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val participants by
        remember(repository, eventId) { repository.observeParticipants(eventId) }
            .collectAsState(initial = emptyList())
    val myStatus by
        remember(repository, eventId, uid) { repository.observeMyStatus(eventId, uid) }
            .collectAsState(initial = null)
    val actionStatus by
        (coordinator?.status ?: flowOf(GroupDriveActionStatus.Idle))
            .collectAsState(initial = GroupDriveActionStatus.Idle)

    GroupDriveScreen(
        participants = participants,
        myStatus = myStatus,
        canJoin = GroupDrive.canJoin(isActiveMember, eventStatus, myRsvp),
        actionStatus = actionStatus,
        onJoin = { coordinator?.let { c -> scope.launch { c.join(eventId) } } },
        onSetStatus = { status -> coordinator?.let { c -> scope.launch { c.updateStatus(eventId, status) } } },
        onLeave = { coordinator?.let { c -> scope.launch { c.leave(eventId) } } },
        onBack = onBack,
    )
}
