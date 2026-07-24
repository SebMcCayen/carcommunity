package com.kungsbackacarcommunity.app.memberprofile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.blocking.BlockedUsersState
import com.kungsbackacarcommunity.app.blocking.BlockingCoordinator
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Integration route for the read-only member profile: builds the coordinator,
 * wires the viewer's block list into the blocking decision, loads once on entry,
 * and drives [MemberProfileScreen].
 *
 * [viewerUid] is required; only [blockingRepository] is optional. In a
 * config-less build it is null, in which case block status is simply not
 * consulted — the block check resolves to "not blocked", the profile read (still
 * rules-gated) proceeds, and the screen offers no block/unblock action.
 *
 * Block and unblock reflect the screen ONLY once their callable reported success,
 * and only from that outcome: a block settles on [MemberProfileState.Blocked]
 * (profile withheld, Unblock offered), an unblock re-loads the profile. Neither
 * re-reads the block list to decide, because the writes come from callables and
 * so get no local latency compensation — a listener subscribed right afterwards
 * can still be serving the pre-change snapshot, which would silently undo the
 * action the user just took. See [MemberProfileCoordinator.markBlocked]. On
 * failure the state is untouched and the screen surfaces the error.
 *
 * The FRIEND action is wired the same way and is equally optional: with a
 * [friendsRepository] the route resolves the viewer's relationship to this
 * member from the viewer's OWN `friend-list` snapshot (see
 * [MemberFriendCoordinator]) and renders the matching control; without one — a
 * config-less build, or the viewer's own profile — no friend action is offered
 * at all. It is deliberately NOT loaded for a blocked/unavailable profile: the
 * screen only renders it on a loaded one, so a member the viewer blocked can
 * never be befriended from the notice that replaces their profile.
 */
@Composable
fun MemberProfileRoute(
    repository: MemberProfileRepository,
    targetUid: String,
    viewerUid: String,
    blockingRepository: BlockingRepository?,
    modifier: Modifier = Modifier,
    friendsRepository: FriendsRepository? = null,
) {
    val coordinator =
        remember(repository, targetUid, blockingRepository, viewerUid) {
            MemberProfileCoordinator(
                targetUid = targetUid,
                repository = repository,
                isBlocked = { candidate ->
                    hasBlocked(blockingRepository, viewerUid, candidate)
                },
            )
        }
    val state by coordinator.state.collectAsState()
    val scope = rememberCoroutineScope()

    val blockingCoordinator =
        remember(blockingRepository) { blockingRepository?.let { BlockingCoordinator(it) } }
    val blockStatus by
        (blockingCoordinator?.actionStatus ?: flowOf(BlockActionStatus.Idle))
            .collectAsState(initial = BlockActionStatus.Idle)

    LaunchedEffect(coordinator) { coordinator.load() }

    // Never offer a friend action on the viewer's OWN profile (the route is
    // reachable with their own uid, e.g. a stale deep link) — the backend
    // rejects a self-request, so the affordance is simply absent rather than
    // present-and-failing, exactly like the block action below.
    val friendCoordinator =
        remember(friendsRepository, targetUid, viewerUid) {
            friendsRepository
                ?.takeIf { targetUid.isNotBlank() && targetUid != viewerUid }
                ?.let { MemberFriendCoordinator(repository = it, targetUid = targetUid) }
        }
    val friendState by
        (friendCoordinator?.state ?: flowOf(null)).collectAsState(initial = null)
    LaunchedEffect(friendCoordinator) { friendCoordinator?.load() }

    // Never offer to block/unblock YOURSELF: the backend rejects a self-block,
    // and the profile route is reachable with the viewer's own uid (e.g. a stale
    // deep link). Guarded here rather than in the screen so the affordance is
    // simply absent instead of present-and-failing.
    val canModerate = blockingCoordinator != null && targetUid.isNotBlank() && targetUid != viewerUid

    MemberProfileScreen(
        state = state,
        // Retry re-reads the friend graph too: its own load failure is silent by
        // design (see MemberFriendCoordinator.load), so without this a profile
        // that recovered on retry could keep an unresolved — and therefore
        // hidden — friend control from the failed first pass.
        onRetry = {
            scope.launch { coordinator.load() }
            scope.launch { friendCoordinator?.load() }
        },
        modifier = modifier,
        // Both actions reflect the profile only once the callable REPORTED
        // SUCCESS, and only from that outcome — never by re-reading the block
        // list, which can still be serving its pre-change cached snapshot (see
        // MemberProfileCoordinator.markBlocked). On failure the state is left
        // alone and blockStatus surfaces the error.
        onBlock =
            if (canModerate) {
                {
                    scope.launch {
                        // No pre-reset: BlockingCoordinator.block guards duplicate
                        // taps via its in-flight (Working) state, which a reset to
                        // Idle would defeat. Mirrors EventChatRoute.
                        blockingCoordinator?.block(targetUid)
                        if (blockingCoordinator?.actionStatus?.value == BlockActionStatus.Done) {
                            coordinator.markBlocked()
                        }
                    }
                }
            } else {
                null
            },
        onUnblock =
            if (canModerate) {
                {
                    scope.launch {
                        blockingCoordinator?.unblock(targetUid)
                        if (blockingCoordinator?.actionStatus?.value == BlockActionStatus.Done) {
                            coordinator.reloadAfterUnblock()
                        }
                    }
                }
            } else {
                null
            },
        blockStatus = blockStatus,
        friendState = friendState,
        // Each action is fire-and-forget from the UI's side: the coordinator
        // owns the in-flight guard (a second tap while one is running returns
        // immediately), the optimistic post-state, and the error.
        onAddFriend = { scope.launch { friendCoordinator?.sendRequest() } },
        onCancelRequest = { scope.launch { friendCoordinator?.cancelRequest() } },
        onAcceptRequest = { scope.launch { friendCoordinator?.acceptRequest() } },
        onDeclineRequest = { scope.launch { friendCoordinator?.declineRequest() } },
    )
}

/**
 * Whether [viewerUid] has blocked [targetUid], read from the viewer's own
 * owner-scoped block list. Best-effort: a null repository, a blank viewer uid, or
 * a non-[BlockedUsersState.Loaded] settled state (including a clean
 * [BlockedUsersState.Error]) resolves to `false` (the profile read still runs and
 * is rules-protected). This only ever reflects who the viewer blocked — never who
 * blocked the viewer.
 *
 * The block-list read is intentionally NOT wrapped in a swallowing `runCatching`:
 * an unexpected exception (and, crucially, [kotlinx.coroutines.CancellationException])
 * propagates to the coordinator, which re-throws cancellation to preserve
 * structured concurrency and surfaces any other failure as
 * [MemberProfileState.Error] — rather than silently masquerading a genuine
 * failure as "not blocked". A *clean* subsystem error is still a settled
 * [BlockedUsersState.Error] state, handled here as best-effort "not blocked".
 */
private suspend fun hasBlocked(
    blockingRepository: BlockingRepository?,
    viewerUid: String,
    targetUid: String,
): Boolean {
    if (blockingRepository == null || viewerUid.isBlank()) return false
    val settled =
        blockingRepository.observeBlocked(viewerUid).first { it !is BlockedUsersState.Loading }
    return settled is BlockedUsersState.Loaded && settled.users.any { it.userId == targetUid }
}
