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
 * a non-[BlockedUsersState.Loaded] outcome resolves to `false` (the profile read
 * still runs and is rules-protected). This only ever reflects who the viewer
 * blocked — never who blocked the viewer.
 */
private suspend fun hasBlocked(
    blockingRepository: BlockingRepository?,
    viewerUid: String,
    targetUid: String,
): Boolean {
    if (blockingRepository == null || viewerUid.isBlank()) return false
    val settled =
        runCatching {
            blockingRepository.observeBlocked(viewerUid).first { it !is BlockedUsersState.Loading }
        }.getOrNull()
    return settled is BlockedUsersState.Loaded && settled.users.any { it.userId == targetUid }
}
