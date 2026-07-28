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
 * notifications.markRead / markAllRead callables and removals through
 * notifications.delete / notifications.deleteAll (all item writes are
 * backend-only).
 */
interface NotificationsRepository {
    fun observeNotifications(uid: String): Flow<NotificationsState>

    suspend fun markRead(notificationId: String)

    suspend fun markAllRead()

    /**
     * Deletes one of the caller's own notifications. Throws on failure — the
     * caller ([NotificationsCoordinator.delete]) needs the failure so it can
     * put the optimistically removed row back.
     */
    suspend fun deleteNotification(notificationId: String)

    /** Empties the caller's inbox. Throws on failure, for the same reason. */
    suspend fun deleteAll()
}
