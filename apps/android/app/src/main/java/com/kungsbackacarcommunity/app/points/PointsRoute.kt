package com.kungsbackacarcommunity.app.points

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/** Points-wallet integration route (Phase 12 slice 15): observe → render. */
@Composable
fun PointsRoute(
    repository: PointsRepository,
    uid: String,
    onBack: () -> Unit,
) {
    val balance by
        remember(repository, uid) { repository.observeBalance(uid) }.collectAsState(initial = null)
    val entriesState by
        remember(repository, uid) { repository.observeEntries(uid) }
            .collectAsState(initial = PointsEntriesState.Loading)
    PointsScreen(balance = balance, entriesState = entriesState, onBack = onBack)
}
