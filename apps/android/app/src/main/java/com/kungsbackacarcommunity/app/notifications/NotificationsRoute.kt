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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Notification inbox integration route (Phase 12 slice 21): wires the inbox
 * stream and the mark-read coordinator into [NotificationsScreen].
 *
 * DELETES. This route owns the optimistic view: the screen is handed the
 * server's list minus the ids the coordinator is currently hiding, so a swipe
 * takes its row away immediately and a delete the server refuses puts it
 * straight back (see [NotificationsCoordinator]). Nothing is copied or mutated
 * — the snapshot stays the source of truth and the hiding is a filter over it —
 * so a restore cannot resurrect a stale version of the row, and an inbox that
 * empties through deleting lands on the same empty state as one that was never
 * filled.
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
 * CONVOYS: [convoyLink] carries the convoy facts a row is re-derived against
 * plus the "open this" callback. It is PASSED IN rather than loaded here, and
 * that is the whole cost story — the shell already holds a convoy-list snapshot
 * for the map's convoy bar, so resolving every convoy row in the inbox costs
 * zero additional reads, no per-row fetch and no listener. Deliberately not a
 * second ConvoyCoordinator (which is what the friend wiring below does): the
 * friend list has no shell-level holder to borrow, convoys do.
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
    convoyLink: ConvoyNotificationLink? = null,
    // False inside the chat hub, whose "Notifications" TAB already names this
    // section; true for the standalone route, where the header is the only
    // thing that does. See [NotificationsScreen].
    showTitle: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val serverState by
        remember(repository, uid) { repository.observeNotifications(uid) }
            .collectAsState(initial = NotificationsState.Loading)

    // Stand-ins for the config-less build, where there is no coordinator and so
    // nothing can be deleted. Remembered UNCONDITIONALLY — a `remember` inside
    // an elvis branch would claim a different composition slot depending on
    // whether the coordinator is null.
    val noPendingDeletes = remember { MutableStateFlow(emptySet<String>()) }
    val noDeleteError = remember { MutableStateFlow<NotificationDeleteError?>(null) }
    val pendingDeletes by (coordinator?.pendingDeletes ?: noPendingDeletes).collectAsState()
    val deleteError by (coordinator?.deleteError ?: noDeleteError).collectAsState()

    // Every snapshot retires the ids whose delete has landed, so the hidden set
    // holds only rows that are still being hidden from something.
    LaunchedEffect(serverState, coordinator) {
        (serverState as? NotificationsState.Loaded)?.let { coordinator?.onSnapshot(it.items) }
    }

    // The optimistic view: the server's list minus the rows a delete has taken
    // out. Filtering here — rather than mutating a copy of the list — is what
    // makes a failed delete a one-line restore (drop the id) instead of a
    // re-insert that has to guess where the row belonged.
    val state =
        when (val current = serverState) {
            is NotificationsState.Loaded ->
                NotificationsState.Loaded(
                    Notifications.visibleItems(current.items, pendingDeletes),
                )
            else -> current
        }
    val visibleIds =
        (state as? NotificationsState.Loaded)?.items?.map { it.id }.orEmpty()

    val actions =
        InboxDeleteActions(
            onDeleteNotification = { id ->
                coordinator?.let { c -> scope.launch { c.delete(id) } }
            },
            onDeleteAll = { coordinator?.let { c -> scope.launch { c.deleteAll(visibleIds) } } },
            deleteError = deleteError,
            onDismissDeleteError = { coordinator?.clearDeleteError() },
        )

    if (friendsRepository == null) {
        NotificationsScreen(
            state = state,
            onMarkRead = markReadHandler(coordinator, scope),
            onMarkAllRead = { coordinator?.let { c -> scope.launch { c.markAllRead() } } },
            onBack = onBack,
            showTitle = showTitle,
            onDeleteNotification = actions.onDeleteNotification,
            onDeleteAll = actions.onDeleteAll,
            deleteError = actions.deleteError,
            onDismissDeleteError = actions.onDismissDeleteError,
            convoyLink = convoyLink,
        )
        return
    }

    FriendAwareNotificationsInbox(
        state = state,
        coordinator = coordinator,
        friendsRepository = friendsRepository,
        scope = scope,
        onBack = onBack,
        deleteActions = actions,
        convoyLink = convoyLink,
        showTitle = showTitle,
    )
}

/**
 * The delete wiring, bundled so the two inbox variants below receive one
 * parameter instead of four identical ones (and cannot drift apart).
 */
private data class InboxDeleteActions(
    val onDeleteNotification: (String) -> Unit,
    val onDeleteAll: () -> Unit,
    val deleteError: NotificationDeleteError?,
    val onDismissDeleteError: () -> Unit,
)

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
    deleteActions: InboxDeleteActions,
    convoyLink: ConvoyNotificationLink?,
    showTitle: Boolean,
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
        showTitle = showTitle,
        pendingFriendRequestIds = pendingByRequester,
        busyFriendRequestIds = busyRows,
        friendActionError = friendActionError,
        onAcceptFriendRequest = { requestId -> scope.launch { friends.accept(requestId) } },
        onDeclineFriendRequest = { requestId -> scope.launch { friends.decline(requestId) } },
        onDismissFriendActionError = { friends.clearActionError() },
        onDeleteNotification = deleteActions.onDeleteNotification,
        onDeleteAll = deleteActions.onDeleteAll,
        deleteError = deleteActions.deleteError,
        onDismissDeleteError = deleteActions.onDismissDeleteError,
        convoyLink = convoyLink,
    )
}

private fun markReadHandler(
    coordinator: NotificationsCoordinator?,
    scope: CoroutineScope,
): (String) -> Unit = { id -> coordinator?.let { c -> scope.launch { c.markRead(id) } } }
