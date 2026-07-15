package com.kungsbackacarcommunity.app.friends

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Friends integration route: builds the coordinator, loads the snapshot on
 * entry (there is no live listener — the callable graph is re-fetched after each
 * mutation), and drives [FriendsScreen] via coordinator actions.
 */
@Composable
fun FriendsRoute(
    repository: FriendsRepository,
    onMessageFriend: (FriendSummary) -> Unit,
    onViewProfile: (FriendSummary) -> Unit,
) {
    val coordinator = remember(repository) { FriendsCoordinator(repository) }
    val status by coordinator.status.collectAsState()
    val addState by coordinator.add.collectAsState()
    val actionError by coordinator.actionError.collectAsState()
    val busyRows by coordinator.busyRows.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(coordinator) { coordinator.load() }

    FriendsScreen(
        status = status,
        addState = addState,
        actionError = actionError,
        busyRows = busyRows,
        onSend = { nickname -> scope.launch { coordinator.sendRequestByNickname(nickname) } },
        onChooseCandidate = { uid -> scope.launch { coordinator.chooseCandidate(uid) } },
        onDismissAdd = { coordinator.resetAdd() },
        onAccept = { requestId -> scope.launch { coordinator.accept(requestId) } },
        onDecline = { requestId -> scope.launch { coordinator.decline(requestId) } },
        onRemove = { friendUid -> scope.launch { coordinator.remove(friendUid) } },
        onClearActionError = { coordinator.clearActionError() },
        onMessageFriend = onMessageFriend,
        onViewProfile = onViewProfile,
    )
}
