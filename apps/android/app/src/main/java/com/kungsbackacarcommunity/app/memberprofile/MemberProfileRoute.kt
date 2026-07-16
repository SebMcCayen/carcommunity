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
 */
@Composable
fun MemberProfileRoute(
    repository: MemberProfileRepository,
    targetUid: String,
    viewerUid: String,
    blockingRepository: BlockingRepository?,
    modifier: Modifier = Modifier,
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

    // Never offer to block/unblock YOURSELF: the backend rejects a self-block,
    // and the profile route is reachable with the viewer's own uid (e.g. a stale
    // deep link). Guarded here rather than in the screen so the affordance is
    // simply absent instead of present-and-failing.
    val canModerate = blockingCoordinator != null && targetUid.isNotBlank() && targetUid != viewerUid

    MemberProfileScreen(
        state = state,
        onRetry = { scope.launch { coordinator.load() } },
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
