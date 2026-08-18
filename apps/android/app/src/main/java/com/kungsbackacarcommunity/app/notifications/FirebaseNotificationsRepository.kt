package com.kungsbackacarcommunity.app.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine

/**
 * [NotificationsRepository] backed by an owner-only Firestore listener on the
 * inbox plus the notifications.markRead / markAllRead / delete / deleteAll
 * callables (europe-west1), Phase 12 slice 21. The listener is bounded to the newest
 * [Notifications.INBOX_QUERY_LIMIT] items (createdAt descending — a
 * single-field orderBy, so Firestore's automatic index suffices); items are
 * additionally sorted newest-first client-side ([Notifications.sortedForInbox])
 * with the same ordering. Guarded ([createIfAvailable]).
 */
class FirebaseNotificationsRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : NotificationsRepository {

    override fun observeNotifications(uid: String): Flow<NotificationsState> = callbackFlow {
        val registration =
            firestore
                .collection(NOTIFICATIONS)
                .document(uid)
                .collection(ITEMS)
                .orderBy(CREATED_AT, Query.Direction.DESCENDING)
                .limit(Notifications.INBOX_QUERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(NotificationsState.Error)
                        return@addSnapshotListener
                    }
                    val items = snapshot?.documents?.mapNotNull { it.toNotification() } ?: emptyList()
                    trySend(NotificationsState.Loaded(Notifications.sortedForInbox(items)))
                }
        awaitClose { registration.remove() }
    }

    override fun observeUnread(uid: String): Flow<Boolean> {
        // Two cheap listeners, both bound while the dot is on screen: the newest
        // inbox item (a limit(1) createdAt-DESC query, the same automatic index
        // observeNotifications uses) and the caller's userPrivate last-seen
        // marker. The dot lights when the newest notification post-dates the
        // marker — the exact mirror of the community chat dot, and lighter than
        // the old full-inbox listener the aggregate used to run.
        val newestCreatedAt: Flow<Long?> = callbackFlow {
            val registration =
                firestore
                    .collection(NOTIFICATIONS)
                    .document(uid)
                    .collection(ITEMS)
                    .orderBy(CREATED_AT, Query.Direction.DESCENDING)
                    .limit(1)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            if ((error as? FirebaseFirestoreException)?.code ==
                                FirebaseFirestoreException.Code.PERMISSION_DENIED
                            ) {
                                // Access revoked (account restriction / rules
                                // gating): hard-clear the dot even if a stale
                                // cached snapshot is present — never keep it lit
                                // for an inbox the user can no longer read.
                                // Mirrors CommunityChatRepository.observeUnread.
                                trySend(null)
                                return@addSnapshotListener
                            }
                            // Other (transient) error with no cached data: keep
                            // the last-known value rather than emitting a
                            // misleading no-unread. With cached data, fall
                            // through and use it.
                            if (snapshot == null) return@addSnapshotListener
                        }
                        trySend(
                            snapshot?.documents?.firstOrNull()
                                ?.getTimestamp(CREATED_AT)?.toDate()?.time,
                        )
                    }
            awaitClose { registration.remove() }
        }
        val lastSeenAt: Flow<Long?> = callbackFlow {
            val registration =
                firestore
                    .collection(USER_PRIVATE)
                    .document(uid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null && snapshot == null) {
                            // Transient failure with no cached marker: keep the
                            // last-known marker rather than momentarily reading it
                            // as missing, which could wrongly re-light the dot.
                            return@addSnapshotListener
                        }
                        trySend(snapshot?.getTimestamp(LAST_SEEN_AT)?.toDate()?.time)
                    }
            awaitClose { registration.remove() }
        }
        return combine(newestCreatedAt, lastSeenAt) { newest, marker ->
            Notifications.hasUnread(newest, marker)
        }
    }

    override suspend fun markRead(notificationId: String) {
        call(MARK_READ, mapOf("notificationId" to notificationId))
    }

    override suspend fun markAllRead() {
        call(MARK_ALL_READ, emptyMap())
    }

    override suspend fun markSeen() {
        call(MARK_SEEN, emptyMap())
    }

    override suspend fun deleteNotification(notificationId: String) {
        call(DELETE, mapOf("notificationId" to notificationId))
    }

    override suspend fun deleteAll() {
        call(DELETE_ALL, emptyMap())
    }

    private suspend fun call(name: String, data: Map<String, Any>) {
        functions.getHttpsCallable(name).call(data)
            .awaitOrThrow { "$name failed without a cause" }
    }

    companion object {
        private const val NOTIFICATIONS = "notifications"
        private const val ITEMS = "items"
        private const val CREATED_AT = "createdAt"
        private const val USER_PRIVATE = "userPrivate"
        private const val LAST_SEEN_AT = "notificationsLastSeenAt"
        private const val REGION = "europe-west1"
        private const val MARK_READ = "notifications-markRead"
        private const val MARK_ALL_READ = "notifications-markAllRead"
        private const val MARK_SEEN = "notifications-markSeen"
        private const val DELETE = "notifications-delete"
        private const val DELETE_ALL = "notifications-deleteAll"

        fun createIfAvailable(context: Context): NotificationsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseNotificationsRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

private fun DocumentSnapshot.toNotification(): AppNotification? {
    if (!exists()) return null
    val title = getString("title") ?: return null
    return AppNotification(
        id = id,
        category = NotificationCategory.fromWire(getString("category")),
        title = title,
        previewText = getString("previewText"),
        body = getString("body"),
        isRead = getBoolean("read") ?: false,
        createdAtMillis = getTimestamp("createdAt")?.toDate()?.time,
        // Both are written by every producer (notifications-core defaults them
        // to 'none'/null), but older inbox rows predate nothing here — the
        // fields have always been persisted. An unknown/absent actionType
        // degrades to NONE, i.e. a plain non-actionable row.
        actionType = NotificationActionType.fromWire(getString("actionType")),
        relatedEntityId = getString("relatedEntityId"),
    )
}
