package com.kungsbackacarcommunity.app.billboards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Billboards integration route (Phase 12 slice 20): observe active billboards
 * and record an `open` interaction on tap (fire-and-forget — a failed
 * analytics write must never block the user).
 *
 * Still hosted by `RouteHost` under
 * [com.kungsbackacarcommunity.app.shell.ShellRoute.Billboards], but nothing in
 * the UI navigates to that route any more — see [BillboardsScreen] for why
 * billboards are deliberately unreachable pending map integration. Kept intact
 * on purpose; do not delete as dead code.
 */
@Composable
fun BillboardsRoute(
    repository: BillboardsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by
        remember(repository) { repository.observeActiveBillboards() }
            .collectAsState(initial = BillboardsState.Loading)

    BillboardsScreen(
        state = state,
        onOpen = { id ->
            scope.launch {
                try {
                    repository.recordInteraction(id, BillboardInteractionType.OPEN)
                } catch (cancellation: CancellationException) {
                    throw cancellation // never swallow coroutine cancellation
                } catch (failure: Exception) {
                    // Fire-and-forget analytics — a failed write never blocks the user.
                }
            }
        },
        onBack = onBack,
    )
}
