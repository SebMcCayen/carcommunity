package com.kungsbackacarcommunity.app.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Partner-stats opt-in integration route (Phase 12 slice 19). */
@Composable
fun PartnerStatsRoute(
    repository: PartnerStatsRepository,
    coordinator: PartnerStatsCoordinator?,
    uid: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current by
        remember(repository, uid) { repository.observeOptIn(uid) }.collectAsState(initial = null)
    val saveStatus by
        (coordinator?.saveStatus ?: flowOf(PartnerStatsSaveStatus.Idle))
            .collectAsState(initial = PartnerStatsSaveStatus.Idle)

    PartnerStatsScreen(
        currentOptIn = current,
        saveStatus = saveStatus,
        onSave = { optIn -> coordinator?.let { c -> scope.launch { c.save(uid, optIn) } } },
        onBack = {
            coordinator?.reset()
            onBack()
        },
    )
}
