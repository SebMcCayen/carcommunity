package com.kungsbackacarcommunity.app.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Account-deletion integration route (Phase 12 slice 25): on a successful
 * deletion, [onDeleted] signs the user out.
 */
@Composable
fun AccountDeletionRoute(
    coordinator: AccountDeletionCoordinator?,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status by
        (coordinator?.status ?: flowOf(AccountDeletionStatus.Idle))
            .collectAsState(initial = AccountDeletionStatus.Idle)

    LaunchedEffect(status) {
        if (status == AccountDeletionStatus.Done) onDeleted()
    }

    AccountDeletionScreen(
        status = status,
        onDelete = { coordinator?.let { c -> scope.launch { c.delete(null) } } },
        onBack = {
            coordinator?.reset()
            onBack()
        },
    )
}
