package com.kungsbackacarcommunity.app.privacy

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Anonymised partner-statistics opt-in (Phase 12 slice 19). A privacy toggle
 * on `userPrivate/{uid}.anonymousPartnerStatsOptIn` — default OFF, owner-only,
 * a direct rules-validated write. Firebase-free interface for testability.
 */
interface PartnerStatsRepository {
    /** The caller's current opt-in; null until read (rendered as off). */
    fun observeOptIn(uid: String): Flow<Boolean?>

    suspend fun setOptIn(uid: String, optIn: Boolean)
}

/** UI-facing status of a save. */
sealed interface PartnerStatsSaveStatus {
    data object Idle : PartnerStatsSaveStatus

    data object Saving : PartnerStatsSaveStatus

    data object Saved : PartnerStatsSaveStatus

    data object Failed : PartnerStatsSaveStatus
}

/** Orchestrates the opt-in write (Phase 12 slice 19). Pure Kotlin. */
class PartnerStatsCoordinator(
    private val repository: PartnerStatsRepository,
) {
    private val state = MutableStateFlow<PartnerStatsSaveStatus>(PartnerStatsSaveStatus.Idle)
    val saveStatus: StateFlow<PartnerStatsSaveStatus> = state.asStateFlow()

    suspend fun save(uid: String, optIn: Boolean) {
        if (state.value == PartnerStatsSaveStatus.Saving) return
        state.value = PartnerStatsSaveStatus.Saving
        try {
            repository.setOptIn(uid, optIn)
            state.value = PartnerStatsSaveStatus.Saved
        } catch (cancellation: CancellationException) {
            state.value = PartnerStatsSaveStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = PartnerStatsSaveStatus.Failed
        }
    }

    fun reset() {
        state.value = PartnerStatsSaveStatus.Idle
    }
}

/**
 * [PartnerStatsRepository] backed by an owner Firestore listener/update on
 * userPrivate/{uid}. Guarded ([createIfAvailable]).
 */
class FirebasePartnerStatsRepository private constructor(
    private val firestore: FirebaseFirestore,
) : PartnerStatsRepository {

    override fun observeOptIn(uid: String): Flow<Boolean?> = callbackFlow {
        val registration =
            firestore.collection(USER_PRIVATE).document(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.getBoolean("anonymousPartnerStatsOptIn"))
            }
        awaitClose { registration.remove() }
    }

    override suspend fun setOptIn(uid: String, optIn: Boolean) {
        val update =
            mapOf(
                "anonymousPartnerStatsOptIn" to optIn,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        suspendCancellableCoroutine { continuation ->
            firestore
                .collection(USER_PRIVATE)
                .document(uid)
                .update(update)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("opt-in write failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val USER_PRIVATE = "userPrivate"

        fun createIfAvailable(context: Context): PartnerStatsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePartnerStatsRepository(FirebaseFirestore.getInstance())
        }
    }
}
