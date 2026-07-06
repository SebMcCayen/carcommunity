package com.kungsbackacarcommunity.app.points

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** UI-facing state of the points transaction list. */
sealed interface PointsEntriesState {
    data object Loading : PointsEntriesState

    data object Error : PointsEntriesState

    data class Loaded(val entries: List<PointsEntry>) : PointsEntriesState
}

/** Read-only points-wallet access (Phase 12 slice 15). Firebase-free for tests. */
interface PointsRepository {
    /** Current balance; null until the first read (rendered as 0). */
    fun observeBalance(uid: String): Flow<Long?>

    fun observeEntries(uid: String): Flow<PointsEntriesState>
}

/**
 * [PointsRepository] backed by owner-only Firestore listeners on
 * pointsLedger/{uid} (balance) and its entries subcollection. Guarded.
 */
class FirebasePointsRepository private constructor(
    private val firestore: FirebaseFirestore,
) : PointsRepository {

    override fun observeBalance(uid: String): Flow<Long?> = callbackFlow {
        val registration =
            firestore.collection(LEDGER).document(uid).addSnapshotListener { snapshot, error ->
                // Keep the last known balance on a transient error rather than
                // emitting null (which would misrender as 0).
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.getLong("balance"))
            }
        awaitClose { registration.remove() }
    }

    override fun observeEntries(uid: String): Flow<PointsEntriesState> = callbackFlow {
        // Bound the "recent transactions" read: newest-first with a limit, then
        // keep the null-safe client sort, so the listener cost stays flat as the
        // ledger grows.
        val registration =
            firestore
                .collection(LEDGER)
                .document(uid)
                .collection(ENTRIES)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(ENTRY_PAGE_SIZE)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(PointsEntriesState.Error)
                        return@addSnapshotListener
                    }
                    val entries = snapshot?.documents?.mapNotNull { it.toEntry() } ?: emptyList()
                    trySend(PointsEntriesState.Loaded(Points.sortedForList(entries)))
                }
        awaitClose { registration.remove() }
    }

    companion object {
        private const val LEDGER = "pointsLedger"
        private const val ENTRIES = "entries"

        /** Cap the recent-transactions read. */
        private const val ENTRY_PAGE_SIZE = 100L

        fun createIfAvailable(context: Context): PointsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePointsRepository(FirebaseFirestore.getInstance())
        }
    }
}

private fun DocumentSnapshot.toEntry(): PointsEntry? {
    if (!exists()) return null
    val amount = getLong("amount") ?: return null
    return PointsEntry(
        id = id,
        amount = amount,
        balanceAfter = getLong("balanceAfter"),
        description = getString("description") ?: "",
        createdAtMillis = getTimestamp("createdAt")?.toDate()?.time,
    )
}
