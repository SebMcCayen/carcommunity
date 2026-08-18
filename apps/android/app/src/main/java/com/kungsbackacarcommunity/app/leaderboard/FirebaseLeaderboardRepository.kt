package com.kungsbackacarcommunity.app.leaderboard

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.kungsbackacarcommunity.app.crownhunt.CrownSeasonClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [LeaderboardRepository] backed by a single rules-gated Firestore listener on the
 * precomputed `leaderboards/{scope}` document.
 *
 * The scope is resolved to a document id by [LeaderboardBoard.scopeDocId]: the
 * all-time board is the fixed `alltime` id, the monthly board is the current
 * Europe/Stockholm `YYYY-MM` season id from [CrownSeasonClock] — the exact month
 * the backend generator writes. Nothing here writes; the read rule (firestore.rules
 * `leaderboards/{scope}` → `allow read: if isActiveMember()`) is the whole security
 * surface, and the pure fold in [LeaderboardBoard] turns the document map into the
 * UI model, so this class only extracts raw rows and forwards listener lifecycle.
 */
class FirebaseLeaderboardRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val seasonIdProvider: () -> String = { CrownSeasonClock.currentSeasonId() },
) : LeaderboardRepository {

    override fun observeBoard(
        scope: LeaderboardScope,
        viewerUid: String?,
    ): Flow<LeaderboardUiState> = callbackFlow {
        trySend(LeaderboardUiState.Loading)
        val docId = LeaderboardBoard.scopeDocId(scope, seasonIdProvider())
        val registration =
            firestore.collection(COLLECTION).document(docId).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(LeaderboardUiState.Error)
                    return@addSnapshotListener
                }
                // A missing document (a month with no board yet, or the very first
                // run) is a valid EMPTY board, not an error: every scope category
                // renders its friendly empty state via LeaderboardBoard.board.
                val raw = snapshot?.rawCategories() ?: emptyMap()
                trySend(
                    LeaderboardUiState.Loaded(
                        scope = scope,
                        categories = LeaderboardBoard.board(scope, raw, viewerUid),
                    ),
                )
            }
        awaitClose { registration.remove() }
    }

    companion object {
        private const val COLLECTION = "leaderboards"

        /**
         * A leaderboard repository, or null in a config-less/CI build (no Firebase)
         * — so the screen shows its loading affordance rather than crashing, exactly
         * as the crown stats layer degrades.
         */
        fun createIfAvailable(context: Context): LeaderboardRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseLeaderboardRepository(FirebaseFirestore.getInstance())
        }
    }
}

/**
 * Extracts the per-category raw rows from a `leaderboards/{scope}` snapshot. Reads
 * the document's `categories` map; each value is an ordered array of row maps
 * `{ rank, uid, displayName, avatarPath, value }`. A row missing its uid or
 * displayName is dropped (it cannot be shown as an anonymous line) — though the
 * server never publishes such a row. Order is preserved verbatim; the server has
 * already ranked each array.
 */
private fun DocumentSnapshot.rawCategories(): Map<String, List<RawLeaderboardRow>> {
    if (!exists()) return emptyMap()
    @Suppress("UNCHECKED_CAST")
    val categories = get("categories") as? Map<String, Any?> ?: return emptyMap()
    val result = mutableMapOf<String, List<RawLeaderboardRow>>()
    for ((key, value) in categories) {
        val rows = value as? List<*> ?: continue
        result[key] =
            rows.mapNotNull { raw ->
                @Suppress("UNCHECKED_CAST")
                val row = raw as? Map<String, Any?> ?: return@mapNotNull null
                val uid = (row["uid"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val displayName = (row["displayName"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RawLeaderboardRow(
                    rank = (row["rank"] as? Number)?.toInt() ?: 0,
                    uid = uid,
                    displayName = displayName,
                    avatarPath = (row["avatarPath"] as? String)?.takeIf { it.isNotEmpty() },
                    value = (row["value"] as? Number)?.toDouble() ?: 0.0,
                )
            }
    }
    return result
}
