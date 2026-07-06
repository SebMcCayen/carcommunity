package com.kungsbackacarcommunity.app.account

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Account deletion (Phase 12 slice 25). A signedIn callable that soft-deletes
 * now with a scheduled 30-day hard purge; works while suspended. Firebase-free
 * interface for testability.
 */
interface AccountDeletionRepository {
    suspend fun deleteAccount(reason: String?)
}

/** UI-facing status of an account deletion. */
sealed interface AccountDeletionStatus {
    data object Idle : AccountDeletionStatus

    data object Deleting : AccountDeletionStatus

    /** Deletion succeeded; the caller signs the user out. */
    data object Done : AccountDeletionStatus

    data object Failed : AccountDeletionStatus
}

/**
 * Orchestrates account deletion (Phase 12 slice 25). Pure Kotlin so it is
 * unit-testable with a fake repository.
 */
class AccountDeletionCoordinator(
    private val repository: AccountDeletionRepository,
) {
    private val state = MutableStateFlow<AccountDeletionStatus>(AccountDeletionStatus.Idle)
    val status: StateFlow<AccountDeletionStatus> = state.asStateFlow()

    suspend fun delete(reason: String?) {
        if (state.value == AccountDeletionStatus.Deleting) return
        state.value = AccountDeletionStatus.Deleting
        try {
            repository.deleteAccount(reason)
            state.value = AccountDeletionStatus.Done
        } catch (cancellation: CancellationException) {
            state.value = AccountDeletionStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = AccountDeletionStatus.Failed
        }
    }

    fun reset() {
        if (state.value == AccountDeletionStatus.Failed) state.value = AccountDeletionStatus.Idle
    }
}

/**
 * [AccountDeletionRepository] backed by the account-deleteAccount callable
 * (europe-west1). Guarded ([createIfAvailable]).
 */
class FirebaseAccountDeletionRepository private constructor(
    private val functions: FirebaseFunctions,
) : AccountDeletionRepository {

    override suspend fun deleteAccount(reason: String?) {
        val data =
            buildMap<String, Any> {
                reason?.trim()?.takeIf { it.isNotEmpty() }?.let { put("reason", it) }
            }
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(CALLABLE)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$CALLABLE failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val CALLABLE = "account-deleteAccount"

        fun createIfAvailable(context: Context): AccountDeletionRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseAccountDeletionRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}
