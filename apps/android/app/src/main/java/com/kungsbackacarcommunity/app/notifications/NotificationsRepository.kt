package com.kungsbackacarcommunity.app.notifications

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the notification inbox. */
sealed interface NotificationsState {
    data object Loading : NotificationsState

    data object Error : NotificationsState

    data class Loaded(val items: List<AppNotification>) : NotificationsState
}

/**
 * In-app notification operations (Phase 12 slice 21). Firebase-free interface
 * so the screen/coordinator logic is unit-testable with fakes.
 *
 * The inbox is an owner-only Firestore read; read-state changes go through the
 * notifications.markRead / markAllRead callables (all item writes are
 * backend-only).
 */
interface NotificationsRepository {
    fun observeNotifications(uid: String): Flow<NotificationsState>

    suspend fun markRead(notificationId: String)

    suspend fun markAllRead()
}
