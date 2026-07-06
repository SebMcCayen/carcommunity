package com.kungsbackacarcommunity.app.partners

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

/** Submits a partner application via the callable (Phase 12 slice 18). */
interface PartnerApplicationRepository {
    suspend fun submit(input: PartnerApplicationInput)
}

/** UI-facing status of an application submission. */
sealed interface PartnerApplicationStatus {
    data object Idle : PartnerApplicationStatus

    data object Submitting : PartnerApplicationStatus

    data object Done : PartnerApplicationStatus

    data object Failed : PartnerApplicationStatus
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
            state.value = PartnerApplicationStatus.Failed
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
