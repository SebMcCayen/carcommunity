package com.kungsbackacarcommunity.app.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Notification inbox integration route (Phase 12 slice 21): wires the inbox
 * stream and the mark-read coordinator into [NotificationsScreen].
 */
@Composable
fun NotificationsRoute(
    repository: NotificationsRepository,
    coordinator: NotificationsCoordinator?,
    uid: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by
        remember(repository, uid) { repository.observeNotifications(uid) }
            .collectAsState(initial = NotificationsState.Loading)

    NotificationsScreen(
        state = state,
        onMarkRead = { id -> coordinator?.let { c -> scope.launch { c.markRead(id) } } },
        onMarkAllRead = { coordinator?.let { c -> scope.launch { c.markAllRead() } } },
        onBack = onBack,
    )
}
