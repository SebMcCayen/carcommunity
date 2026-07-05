package com.kungsbackacarcommunity.app.partners

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Partner-application integration route (Phase 12 slice 18). */
@Composable
fun PartnerApplicationRoute(
    coordinator: PartnerApplicationCoordinator?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status by
        (coordinator?.status ?: flowOf(PartnerApplicationStatus.Idle))
            .collectAsState(initial = PartnerApplicationStatus.Idle)

    PartnerApplicationScreen(
        status = status,
        onSubmit = { input -> coordinator?.let { c -> scope.launch { c.submit(input) } } },
        onBack = {
            coordinator?.reset()
            onBack()
        },
    )
}
