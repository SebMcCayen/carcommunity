package com.kungsbackacarcommunity.app.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [NotificationsRepository] backed by an owner-only Firestore listener on the
 * inbox plus the notifications.markRead / markAllRead callables (europe-west1),
 * Phase 12 slice 21. The listener is bounded to the newest
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

    override suspend fun markRead(notificationId: String) {
        call(MARK_READ, mapOf("notificationId" to notificationId))
    }

    override suspend fun markAllRead() {
        call(MARK_ALL_READ, emptyMap())
    }

    private suspend fun call(name: String, data: Map<String, Any>) {
        functions.getHttpsCallable(name).call(data)
            .awaitOrThrow { "$name failed without a cause" }
    }

    companion object {
        private const val NOTIFICATIONS = "notifications"
        private const val ITEMS = "items"
        private const val CREATED_AT = "createdAt"
        private const val REGION = "europe-west1"
        private const val MARK_READ = "notifications-markRead"
        private const val MARK_ALL_READ = "notifications-markAllRead"

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
