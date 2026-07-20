package com.kungsbackacarcommunity.app.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.diagnostics.FirebaseClientErrorReporter
import com.kungsbackacarcommunity.app.friends.FriendsCoordinator
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Notification inbox integration route (Phase 12 slice 21): wires the inbox
 * stream and the mark-read coordinator into [NotificationsScreen].
 *
 * FRIEND REQUESTS: when a [friendsRepository] is available the route also owns
 * a [FriendsCoordinator], so a friend-request row can be accepted or declined
 * without leaving the inbox. It reuses the Friends page's coordinator wholesale
 * rather than reimplementing the callable — that is where the `friend-list` /
 * `friend-respondRequest` contract, the per-row in-flight guard, the error
 * mapping and the deliberate "do not file an issue for a business refusal" rule
 * already live, and a second implementation would be a second place for those
 * to drift.
 *
 * A config-less build (no Firebase) passes null and the inbox renders exactly as
 * it did before, with no friend actions and no extra callable traffic. The two
 * cases are separate composables rather than one with nullable state so neither
 * ends up conditionally collecting flows.
 */
@Composable
fun NotificationsRoute(
    repository: NotificationsRepository,
    coordinator: NotificationsCoordinator?,
    uid: String,
    onBack: () -> Unit,
    friendsRepository: FriendsRepository? = null,
) {
    val scope = rememberCoroutineScope()
    val state by
        remember(repository, uid) { repository.observeNotifications(uid) }
            .collectAsState(initial = NotificationsState.Loading)

    if (friendsRepository == null) {
        NotificationsScreen(
            state = state,
            onMarkRead = markReadHandler(coordinator, scope),
            onMarkAllRead = { coordinator?.let { c -> scope.launch { c.markAllRead() } } },
            onBack = onBack,
        )
        return
    }

    FriendAwareNotificationsInbox(
        state = state,
        coordinator = coordinator,
        friendsRepository = friendsRepository,
        scope = scope,
        onBack = onBack,
    )
}

/**
 * The inbox with friend-request actions wired in.
 *
 * The actionable-request map is rebuilt from every `friend-list` snapshot, and
 * the coordinator re-fetches after each response — INCLUDING a failed one — so
 * a request that was answered elsewhere loses its buttons on the next snapshot
 * instead of lingering as a control that does nothing. Nothing here is
 * optimistic: the row's state is always derived from the server's latest
 * answer, so a refused accept can never leave the row claiming a friendship.
 */
@Composable
private fun FriendAwareNotificationsInbox(
    state: NotificationsState,
    coordinator: NotificationsCoordinator?,
    friendsRepository: FriendsRepository,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val friends =
        remember(friendsRepository, context) {
            FriendsCoordinator(
                friendsRepository,
                FirebaseClientErrorReporter.createIfAvailable(context),
            )
        }
    val friendsStatus by friends.status.collectAsState()
    val friendActionError by friends.actionError.collectAsState()
    val busyRows by friends.busyRows.collectAsState()

    LaunchedEffect(friends) { friends.load() }

    // Requester uid -> still-pending request id. Only INCOMING requests: an
    // outgoing one is not ours to answer, and a request that has been accepted
    // or declined is no longer in either list at all.
    val pendingByRequester =
        remember(friendsStatus) {
            (friendsStatus as? FriendsStatus.Loaded)
                ?.incoming
                ?.associate { it.fromUid to it.requestId }
                .orEmpty()
        }

    NotificationsScreen(
        state = state,
        onMarkRead = markReadHandler(coordinator, scope),
        onMarkAllRead = { coordinator?.let { c -> scope.launch { c.markAllRead() } } },
        onBack = onBack,
        pendingFriendRequestIds = pendingByRequester,
        busyFriendRequestIds = busyRows,
        friendActionError = friendActionError,
        onAcceptFriendRequest = { requestId -> scope.launch { friends.accept(requestId) } },
        onDeclineFriendRequest = { requestId -> scope.launch { friends.decline(requestId) } },
        onDismissFriendActionError = { friends.clearActionError() },
    )
}

private fun markReadHandler(
    coordinator: NotificationsCoordinator?,
    scope: CoroutineScope,
): (String) -> Unit = { id -> coordinator?.let { c -> scope.launch { c.markRead(id) } } }
