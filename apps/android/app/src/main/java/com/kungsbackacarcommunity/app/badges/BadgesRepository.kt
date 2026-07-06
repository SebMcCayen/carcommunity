package com.kungsbackacarcommunity.app.badges

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** UI-facing state of the badges list. */
sealed interface BadgesState {
    data object Loading : BadgesState

    data object Error : BadgesState

    data class Loaded(val badges: List<Badge>) : BadgesState
}

/** Read-only badges access (Phase 12 slice 14). Firebase-free for testability. */
interface BadgesRepository {
    fun observeBadges(uid: String): Flow<BadgesState>
}

/**
 * [BadgesRepository] backed by an owner-only Firestore listener on
 * users/{uid}/badges. Guarded ([createIfAvailable]).
 */
class FirebaseBadgesRepository private constructor(
    private val firestore: FirebaseFirestore,
) : BadgesRepository {

    override fun observeBadges(uid: String): Flow<BadgesState> = callbackFlow {
        val registration =
            firestore
                .collection(USERS)
                .document(uid)
                .collection(BADGES)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(BadgesState.Error)
                        return@addSnapshotListener
                    }
                    val badges = snapshot?.documents?.mapNotNull { it.toBadge() } ?: emptyList()
                    trySend(BadgesState.Loaded(Badges.sortedForList(badges)))
                }
        awaitClose { registration.remove() }
    }

    companion object {
        private const val USERS = "users"
        private const val BADGES = "badges"

        fun createIfAvailable(context: Context): BadgesRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseBadgesRepository(FirebaseFirestore.getInstance())
        }
    }
}

private fun DocumentSnapshot.toBadge(): Badge? {
    if (!exists()) return null
    return Badge(
        key = getString("badgeKey") ?: id,
        fallbackName = getString("name"),
        awardedAtMillis = getTimestamp("awardedAt")?.toDate()?.time,
    )
}
