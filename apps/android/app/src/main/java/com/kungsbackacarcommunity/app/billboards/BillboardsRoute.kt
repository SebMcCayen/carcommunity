package com.kungsbackacarcommunity.app.billboards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Billboards integration route (Phase 12 slice 20): observe active billboards
 * and record an `open` interaction on tap (fire-and-forget — a failed
 * analytics write must never block the user).
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
            scope.launch { runCatching { repository.recordInteraction(id, BillboardInteractionType.OPEN) } }
        },
        onBack = onBack,
    )
}
