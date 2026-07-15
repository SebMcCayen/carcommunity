package com.kungsbackacarcommunity.app.memberprofile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.kungsbackacarcommunity.app.blocking.BlockedUsersState
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Integration route for the read-only member profile: builds the coordinator,
 * wires the viewer's block list into the blocking decision, loads once on entry,
 * and drives [MemberProfileScreen].
 *
 * The [blockingRepository]/[viewerUid] pair is optional — in a config-less build
 * (or when the block list can't be read) the block check simply resolves to
 * "not blocked" and the profile read (still rules-gated) proceeds.
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

    LaunchedEffect(coordinator) { coordinator.load() }

    MemberProfileScreen(
        state = state,
        onRetry = { scope.launch { coordinator.load() } },
        modifier = modifier,
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
