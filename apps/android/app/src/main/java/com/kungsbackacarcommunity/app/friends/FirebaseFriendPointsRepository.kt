package com.kungsbackacarcommunity.app.friends

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.kungsbackacarcommunity.app.firebase.await
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * [FriendPointsRepository] backed by rules-gated `get()` reads of
 * `pointsLedger/{uid}` — the same public balance document the member profile
 * reads. Guarded ([createIfAvailable]): a config-less/CI build (no Firebase)
 * gets null, so the friends list simply shows no points chip rather than
 * crashing.
 *
 * The reads fan out one `get()` per uid (the rule grants `get`, never `list`, so
 * a single collection query is not permitted and would be denied). They run
 * concurrently and are each best-effort: a missing wallet or a failed read drops
 * that uid from the result rather than failing the batch, matching the profile's
 * own "degrade to 0" handling.
 */
class FirebaseFriendPointsRepository private constructor(
    private val firestore: FirebaseFirestore,
) : FriendPointsRepository {

    override suspend fun balancesFor(uids: List<String>): Map<String, Long> {
        val distinct = uids.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return coroutineScope {
            distinct
                .map { uid ->
                    async {
                        runCatchingCancellable {
                            firestore
                                .collection(POINTS_LEDGER)
                                .document(uid)
                                .get()
                                .await()
                                .let { snapshot -> (snapshot.get("balance") as? Number)?.toLong() }
                        }
                            .getOrNull()
                            ?.let { balance -> uid to balance }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }
    }

    companion object {
        private const val POINTS_LEDGER = "pointsLedger"

        fun createIfAvailable(context: Context): FriendPointsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseFriendPointsRepository(FirebaseFirestore.getInstance())
        }
    }
}
