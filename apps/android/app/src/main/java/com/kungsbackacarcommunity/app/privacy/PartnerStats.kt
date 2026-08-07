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
 * Observed anonymised-partner-statistics consent. Deliberately distinguishes a
 * read that has NOT resolved (or failed) from one that resolved to "no explicit
 * choice": a transient Firestore error must NEVER be mistaken for default-on and
 * silently overwrite an explicitly opted-out member on the next Save.
 */
sealed interface PartnerStatsConsentState {
    /** Not yet read, or the read failed. The UI shows the default-on value but
     * must NOT let it be persisted (Save disabled) until a definitive read. */
    data object Unknown : PartnerStatsConsentState

    /** Read succeeded with no explicit choice stored → default-on (opt-out). */
    data object DefaultOn : PartnerStatsConsentState

    /** Read succeeded with an explicit stored choice. */
    data class Chosen(val optIn: Boolean) : PartnerStatsConsentState
}

/**
 * Anonymised partner-statistics consent (Phase 12 slice 19). A privacy toggle
 * on `userPrivate/{uid}.anonymousPartnerStatsOptIn` — DEFAULT-ON / opt-out,
 * owner-only, a direct rules-validated write. A missing field (no explicit
 * choice) is treated as ON both here and in the backend recordInteraction gate,
 * which excludes a member only on an explicit `false`. Firebase-free interface
 * for testability.
 */
interface PartnerStatsRepository {
    /** The caller's consent state: [PartnerStatsConsentState.Unknown] until the
     * first snapshot resolves (or on read error), then DefaultOn / Chosen. */
    fun observeConsent(uid: String): Flow<PartnerStatsConsentState>

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

    override fun observeConsent(uid: String): Flow<PartnerStatsConsentState> = callbackFlow {
        val registration =
            firestore.collection(USER_PRIVATE).document(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // A read error is NOT "no explicit choice": surface Unknown so
                    // the UI never overwrites a real (possibly opted-out) value
                    // with the default-on assumption.
                    trySend(PartnerStatsConsentState.Unknown)
                    return@addSnapshotListener
                }
                val stored = snapshot?.getBoolean("anonymousPartnerStatsOptIn")
                trySend(
                    if (stored == null) {
                        PartnerStatsConsentState.DefaultOn
                    } else {
                        PartnerStatsConsentState.Chosen(stored)
                    },
                )
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
