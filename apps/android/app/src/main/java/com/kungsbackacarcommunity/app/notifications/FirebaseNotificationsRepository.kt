package com.kungsbackacarcommunity.app.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [NotificationsRepository] backed by an owner-only Firestore listener on the
 * inbox plus the notifications.markRead / markAllRead callables (europe-west1),
 * Phase 12 slice 21. Items are sorted newest-first client-side (no index).
 * Guarded ([createIfAvailable]).
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

    private suspend fun call(name: String, data: Map<String, Any>): Unit =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$name failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val NOTIFICATIONS = "notifications"
        private const val ITEMS = "items"
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
    )
}
