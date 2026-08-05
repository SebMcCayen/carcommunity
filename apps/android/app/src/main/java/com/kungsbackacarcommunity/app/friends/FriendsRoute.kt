package com.kungsbackacarcommunity.app.friends

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.diagnostics.ClientErrorReporter
import com.kungsbackacarcommunity.app.diagnostics.FirebaseClientErrorReporter
import kotlinx.coroutines.launch

/**
 * Friends integration route: builds the coordinator, loads the snapshot on
 * entry (there is no live listener — the callable graph is re-fetched after each
 * mutation), and drives [FriendsScreen] via coordinator actions.
 *
 * [errorReporter] is threaded into the coordinator so an UNCLASSIFIED friends
 * failure — the "Something went wrong" case — files a deduplicated GitHub issue
 * via the shared `errors-reportClientError` pipeline instead of vanishing.
 * Defaulted here (rather than threaded from the caller) so tests can inject a
 * fake; a config-less build gets null and simply skips reporting.
 */
@Composable
fun FriendsRoute(
    repository: FriendsRepository,
    onMessageFriend: (FriendSummary) -> Unit,
    onViewProfile: (FriendSummary) -> Unit,
    errorReporter: ClientErrorReporter? = defaultClientErrorReporter(),
) {
    val coordinator = remember(repository, errorReporter) {
        FriendsCoordinator(repository, errorReporter)
    }
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
        onCancel = { toUid -> scope.launch { coordinator.cancel(toUid) } },
        onRemove = { friendUid -> scope.launch { coordinator.remove(friendUid) } },
        onClearActionError = { coordinator.clearActionError() },
        onMessageFriend = onMessageFriend,
        onViewProfile = onViewProfile,
    )
}

/**
 * Builds the Firebase-backed [ClientErrorReporter] from the local context, or
 * null in a config-less build (mirrors the Messages route's default).
 */
@Composable
private fun defaultClientErrorReporter(): ClientErrorReporter? {
    val context = LocalContext.current
    return remember(context) { FirebaseClientErrorReporter.createIfAvailable(context) }
}
