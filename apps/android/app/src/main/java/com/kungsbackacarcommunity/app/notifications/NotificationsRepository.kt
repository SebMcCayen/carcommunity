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
 *
 * The Notifications red DOT is separate from per-item read state: it is a
 * last-SEEN marker ([observeUnread] / [markSeen]) that mirrors community chat's
 * last-read marker, so opening the inbox clears the dot without marking every
 * row read.
 */
interface NotificationsRepository {
    fun observeNotifications(uid: String): Flow<NotificationsState>

    /**
     * Live "has unseen" flag for [uid]: true while the newest notification is
     * newer than the caller's last-seen marker
     * (`userPrivate/{uid}.notificationsLastSeenAt`). Emits false once [markSeen]
     * runs (or while the inbox is empty). Drives the aggregate map chat-bubble
     * dot and the hub's Notifications tab dot — the exact mirror of
     * [com.kungsbackacarcommunity.app.chatchannels.CommunityChatRepository.observeUnread].
     */
    fun observeUnread(uid: String): Flow<Boolean>

    suspend fun markRead(notificationId: String)

    suspend fun markAllRead()

    /**
     * `notifications-markSeen` — stamps the caller's last-seen marker so the
     * red dot clears. Idempotent, best-effort (the caller swallows failures).
     * Deliberately NOT the same as [markAllRead]: this clears the dot without
     * flipping any item's read flag, so per-row unread styling survives opening
     * the inbox (mirrors community chat's markRead on channel open).
     */
    suspend fun markSeen()

    /**
     * Deletes one of the caller's own notifications. Throws on failure — the
     * caller ([NotificationsCoordinator.delete]) needs the failure so it can
     * put the optimistically removed row back.
     */
    suspend fun deleteNotification(notificationId: String)

    /** Empties the caller's inbox. Throws on failure, for the same reason. */
    suspend fun deleteAll()
}
