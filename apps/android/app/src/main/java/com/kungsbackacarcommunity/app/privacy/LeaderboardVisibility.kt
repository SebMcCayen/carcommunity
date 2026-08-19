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
 * Observed leaderboard-visibility choice. Mirrors [PartnerStatsConsentState]:
 * a read that has NOT resolved (or failed) is deliberately distinguished from
 * one that resolved to "no explicit choice", so a transient Firestore error is
 * never mistaken for the default (shown) and silently re-lists a member who has
 * explicitly opted out.
 */
sealed interface LeaderboardVisibilityState {
    /** Not yet read, or the read failed. The UI shows the default (shown) value
     * but must NOT let it be persisted (Save disabled) until a definitive read. */
    data object Unknown : LeaderboardVisibilityState

    /** Read succeeded with no explicit choice stored → default-shown (visible). */
    data object DefaultShown : LeaderboardVisibilityState

    /** Read succeeded with an explicit stored choice (`leaderboardOptOut`). */
    data class Chosen(val optOut: Boolean) : LeaderboardVisibilityState
}

/**
 * Leaderboard-visibility toggle backed by `userPrivate/{uid}.leaderboardOptOut`
 * — a direct, rules-validated owner write (the field is whitelisted in
 * firestore.rules validUserPrivateUpdate). DEFAULT-SHOWN / opt-out: a missing
 * field (no explicit choice) means the member is visible, and the backend
 * leaderboard generator excludes a member only on an explicit `true`. The public
 * website reuses the same generator, so opting out hides the member in-app AND on
 * the site. Firebase-free interface for testability; mirrors
 * [PartnerStatsRepository].
 */
interface LeaderboardVisibilityRepository {
    /** The caller's visibility state: [LeaderboardVisibilityState.Unknown] until
     * the first snapshot resolves (or on read error), then DefaultShown / Chosen. */
    fun observeVisibility(uid: String): Flow<LeaderboardVisibilityState>

    suspend fun setOptOut(uid: String, optOut: Boolean)
}

/** UI-facing status of a save. Mirrors [PartnerStatsSaveStatus]. */
sealed interface LeaderboardVisibilitySaveStatus {
    data object Idle : LeaderboardVisibilitySaveStatus

    data object Saving : LeaderboardVisibilitySaveStatus

    data object Saved : LeaderboardVisibilitySaveStatus

    data object Failed : LeaderboardVisibilitySaveStatus
}

/** Orchestrates the opt-out write. Pure Kotlin; mirrors [PartnerStatsCoordinator]. */
class LeaderboardVisibilityCoordinator(
    private val repository: LeaderboardVisibilityRepository,
) {
    private val state = MutableStateFlow<LeaderboardVisibilitySaveStatus>(LeaderboardVisibilitySaveStatus.Idle)
    val saveStatus: StateFlow<LeaderboardVisibilitySaveStatus> = state.asStateFlow()

    suspend fun save(uid: String, optOut: Boolean) {
        if (state.value == LeaderboardVisibilitySaveStatus.Saving) return
        state.value = LeaderboardVisibilitySaveStatus.Saving
        try {
            repository.setOptOut(uid, optOut)
            state.value = LeaderboardVisibilitySaveStatus.Saved
        } catch (cancellation: CancellationException) {
            state.value = LeaderboardVisibilitySaveStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = LeaderboardVisibilitySaveStatus.Failed
        }
    }

    fun reset() {
        state.value = LeaderboardVisibilitySaveStatus.Idle
    }
}

/**
 * [LeaderboardVisibilityRepository] backed by an owner Firestore listener/update
 * on userPrivate/{uid}. Guarded ([createIfAvailable]); mirrors
 * [FirebasePartnerStatsRepository].
 */
class FirebaseLeaderboardVisibilityRepository private constructor(
    private val firestore: FirebaseFirestore,
) : LeaderboardVisibilityRepository {

    override fun observeVisibility(uid: String): Flow<LeaderboardVisibilityState> = callbackFlow {
        val registration =
            firestore.collection(USER_PRIVATE).document(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // A read error is NOT "no explicit choice": surface Unknown so
                    // the UI never overwrites a real (possibly opted-out) value
                    // with the default-shown assumption.
                    trySend(LeaderboardVisibilityState.Unknown)
                    return@addSnapshotListener
                }
                val stored = snapshot?.getBoolean("leaderboardOptOut")
                trySend(
                    if (stored == null) {
                        LeaderboardVisibilityState.DefaultShown
                    } else {
                        LeaderboardVisibilityState.Chosen(stored)
                    },
                )
            }
        awaitClose { registration.remove() }
    }

    override suspend fun setOptOut(uid: String, optOut: Boolean) {
        val update =
            mapOf(
                "leaderboardOptOut" to optOut,
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
                            task.exception ?: IllegalStateException("opt-out write failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val USER_PRIVATE = "userPrivate"

        fun createIfAvailable(context: Context): LeaderboardVisibilityRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseLeaderboardVisibilityRepository(FirebaseFirestore.getInstance())
        }
    }
}
