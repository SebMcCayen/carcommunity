package com.kungsbackacarcommunity.app.blocking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Blocking integration route (Phase 12 slice 8): observe the owner's blocked
 * list and unblock via the coordinator. The list observer reflects the removal.
 */
@Composable
fun BlockingRoute(
    repository: BlockingRepository,
    uid: String,
    onBack: () -> Unit,
) {
    val state by
        remember(repository, uid) { repository.observeBlocked(uid) }
            .collectAsState(initial = BlockedUsersState.Loading)
    val coordinator = remember(repository) { BlockingCoordinator(repository) }
    val actionStatus by coordinator.actionStatus.collectAsState()
    val scope = rememberCoroutineScope()

    BlockingScreen(
        state = state,
        actionStatus = actionStatus,
        onUnblock = { targetUserId ->
            scope.launch {
                // Clear any prior terminal status before starting a new action, then
                // let this action's Done/Failed status persist so the screen can
                // surface failures. The list observer reflects a successful removal.
                coordinator.reset()
                coordinator.unblock(targetUserId)
            }
        },
        onBack = onBack,
    )
}
