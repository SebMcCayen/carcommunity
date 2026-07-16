package com.kungsbackacarcommunity.app.blocking

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [BlockingRepository] backed by an owner Firestore listener on
 * `userBlocks/{uid}/blocked` plus the `blocking-block` / `blocking-unblock`
 * callables (europe-west1), Phase 12 slice 8. Guarded ([createIfAvailable]).
 */
class FirebaseBlockingRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : BlockingRepository {

    override fun observeBlocked(uid: String): Flow<BlockedUsersState> = callbackFlow {
        val registration =
            firestore
                .collection(USER_BLOCKS)
                .document(uid)
                .collection(BLOCKED)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(BlockedUsersState.Error)
                        return@addSnapshotListener
                    }
                    val users = snapshot?.documents?.mapNotNull { it.toBlockedUser() } ?: emptyList()
                    trySend(BlockedUsersState.Loaded(BlockedUsers.sortedForList(users)))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun block(targetUserId: String): Unit = call(BLOCK, targetUserId)

    override suspend fun unblock(targetUserId: String): Unit = call(UNBLOCK, targetUserId)

    private suspend fun call(name: String, targetUserId: String) {
        functions.getHttpsCallable(name).call(mapOf<String, Any?>("targetUserId" to targetUserId))
            .awaitOrThrow { "$name failed without a cause" }
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val USER_BLOCKS = "userBlocks"
        private const val BLOCKED = "blocked"
        private const val BLOCK = "blocking-block"
        private const val UNBLOCK = "blocking-unblock"

        fun createIfAvailable(context: Context): BlockingRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseBlockingRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

private fun DocumentSnapshot.toBlockedUser(): BlockedUser? {
    if (!exists()) return null
    return BlockedUser(
        userId = getString("blockedUserId") ?: id,
        displayName = getString("displayName"),
        blockedAtMillis = getTimestamp("createdAt")?.toDate()?.time,
    )
}
