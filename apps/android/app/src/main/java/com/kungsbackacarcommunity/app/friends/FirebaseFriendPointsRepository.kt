package com.kungsbackacarcommunity.app.friends

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.kungsbackacarcommunity.app.firebase.await
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * [FriendPointsRepository] backed by rules-gated `get()` reads of
 * `pointsLedger/{uid}` — the same public balance document the member profile
 * reads. Guarded ([createIfAvailable]): a config-less/CI build (no Firebase)
 * gets null, so no repository is wired and every friend's points simply render
 * as 0 rather than crashing.
 *
 * The reads fan out one `get()` per uid (the rule grants `get`, never `list`, so
 * a single collection query is not permitted and would be denied), bounded to
 * [MAX_CONCURRENT_READS] in-flight requests so a large friends list can't burst
 * into an unbounded read storm. Each read is best-effort: a missing wallet or a
 * failed read drops that uid from the result rather than failing the batch, and
 * the UI renders any dropped/absent uid as 0 — matching the profile's own
 * "degrade to 0" handling.
 */
class FirebaseFriendPointsRepository private constructor(
    private val firestore: FirebaseFirestore,
) : FriendPointsRepository {

    override suspend fun balancesFor(uids: List<String>): Map<String, Long> {
        val distinct = uids.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val gate = Semaphore(MAX_CONCURRENT_READS)
        return coroutineScope {
            distinct
                .map { uid ->
                    async {
                        gate.withPermit {
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
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }
    }

    companion object {
        private const val POINTS_LEDGER = "pointsLedger"

        /** Cap on concurrent `get()` reads so a large friends list can't burst. */
        private const val MAX_CONCURRENT_READS = 8

        fun createIfAvailable(context: Context): FriendPointsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseFriendPointsRepository(FirebaseFirestore.getInstance())
        }
    }
}
