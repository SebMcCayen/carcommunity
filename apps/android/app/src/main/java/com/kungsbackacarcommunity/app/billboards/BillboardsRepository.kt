package com.kungsbackacarcommunity.app.billboards

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

/** UI-facing state of the billboards list. */
sealed interface BillboardsState {
    data object Loading : BillboardsState

    data object Error : BillboardsState

    data class Loaded(val billboards: List<Billboard>) : BillboardsState
}

/** Billboards read + interaction (Phase 12 slice 20). Firebase-free for tests. */
interface BillboardsRepository {
    fun observeActiveBillboards(): Flow<BillboardsState>

    suspend fun recordInteraction(billboardId: String, type: BillboardInteractionType)
}

/**
 * [BillboardsRepository] backed by a Firestore listener on active billboards
 * plus the billboards-recordInteraction callable (europe-west1). Guarded.
 */
class FirebaseBillboardsRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : BillboardsRepository {

    override fun observeActiveBillboards(): Flow<BillboardsState> = callbackFlow {
        val registration =
            firestore
                .collection(BILLBOARDS)
                .whereEqualTo("status", "active")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(BillboardsState.Error)
                        return@addSnapshotListener
                    }
                    trySend(
                        BillboardsState.Loaded(
                            snapshot?.documents?.mapNotNull { it.toBillboard() } ?: emptyList(),
                        ),
                    )
                }
        awaitClose { registration.remove() }
    }

    override suspend fun recordInteraction(billboardId: String, type: BillboardInteractionType) {
        val data = mapOf("billboardId" to billboardId, "interactionType" to type.wire)
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(RECORD_INTERACTION)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$RECORD_INTERACTION failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val BILLBOARDS = "billboards"
        private const val REGION = "europe-west1"
        private const val RECORD_INTERACTION = "billboards-recordInteraction"

        fun createIfAvailable(context: Context): BillboardsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseBillboardsRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

private fun DocumentSnapshot.toBillboard(): Billboard? {
    if (!exists()) return null
    val headline = getString("headline") ?: return null
    return Billboard(
        id = id,
        headline = headline,
        message = getString("message"),
        companyId = getString("partnerCompanyId"),
    )
}
