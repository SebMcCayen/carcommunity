package com.kungsbackacarcommunity.app.partners

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Submits a partner application via the callable (Phase 12 slice 18). */
interface PartnerApplicationRepository {
    suspend fun submit(input: PartnerApplicationInput)
}

/**
 * Reason a partner-application submission failed, derived from the callable's
 * [FirebaseFunctionsException] code so the UI can show an actionable message
 * instead of a mislabeled generic error.
 */
enum class PartnerApplicationFailureReason {
    /** Backend rejected the payload (e.g. a malformed field). */
    INVALID_ARGUMENT,

    /** An application for this user/email is already under review. */
    ALREADY_EXISTS,

    /** Network error, App Check, or any other non-specific failure. */
    UNKNOWN,
}

/** UI-facing status of an application submission. */
sealed interface PartnerApplicationStatus {
    data object Idle : PartnerApplicationStatus

    data object Submitting : PartnerApplicationStatus

    data object Done : PartnerApplicationStatus

    /** Carries the mapped [reason] so the screen can pick a clear message. */
    data class Failed(val reason: PartnerApplicationFailureReason) : PartnerApplicationStatus
}

/** Maps a submission failure to a [PartnerApplicationFailureReason]. */
fun partnerApplicationFailureReasonOf(failure: Throwable): PartnerApplicationFailureReason =
    when ((failure as? FirebaseFunctionsException)?.code) {
        FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
            PartnerApplicationFailureReason.INVALID_ARGUMENT
        FirebaseFunctionsException.Code.ALREADY_EXISTS ->
            PartnerApplicationFailureReason.ALREADY_EXISTS
        else -> PartnerApplicationFailureReason.UNKNOWN
    }

/**
 * Orchestrates the partner-application submission (Phase 12 slice 18). Pure
 * Kotlin so it is unit-testable with a fake repository.
 */
class PartnerApplicationCoordinator(
    private val repository: PartnerApplicationRepository,
) {
    private val state = MutableStateFlow<PartnerApplicationStatus>(PartnerApplicationStatus.Idle)
    val status: StateFlow<PartnerApplicationStatus> = state.asStateFlow()

    suspend fun submit(input: PartnerApplicationInput) {
        if (state.value == PartnerApplicationStatus.Submitting) return
        state.value = PartnerApplicationStatus.Submitting
        try {
            repository.submit(input)
            state.value = PartnerApplicationStatus.Done
        } catch (cancellation: CancellationException) {
            state.value = PartnerApplicationStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = PartnerApplicationStatus.Failed(partnerApplicationFailureReasonOf(failure))
        }
    }

    /** Clears a terminal status (Done or Failed) so the form is fresh again. */
    fun reset() {
        if (state.value != PartnerApplicationStatus.Submitting) {
            state.value = PartnerApplicationStatus.Idle
        }
    }
}

/**
 * [PartnerApplicationRepository] backed by the partners-submitApplication
 * callable (europe-west1). Guarded ([createIfAvailable]).
 */
class FirebasePartnerApplicationRepository private constructor(
    private val functions: FirebaseFunctions,
) : PartnerApplicationRepository {

    override suspend fun submit(input: PartnerApplicationInput) {
        val data =
            buildMap<String, Any> {
                put("companyName", input.companyName)
                put("category", input.category.wire)
                put("contactName", input.contactName)
                put("contactEmail", input.contactEmail)
                input.contactPhone?.let { put("contactPhone", it) }
                input.websiteUrl?.let { put("websiteUrl", it) }
                input.message?.let { put("message", it) }
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
        private const val CALLABLE = "partners-submitApplication"

        fun createIfAvailable(context: Context): PartnerApplicationRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePartnerApplicationRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}
