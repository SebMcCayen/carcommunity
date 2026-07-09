package com.kungsbackacarcommunity.app.feedback

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

/** Result of a successful callable invocation. */
data class FeedbackSubmitResult(
    val reportId: String,
    val issueUrl: String?,
    val created: Boolean,
)

/** Thrown when the per-user rate limit is hit (resource-exhausted). */
class FeedbackRateLimitedException : Exception()

/** Files a "Report a problem" report via the callable (feedback.reportIssue). */
interface FeedbackRepository {
    suspend fun report(input: FeedbackReportInput): FeedbackSubmitResult
}

/** UI-facing status of a report submission. */
sealed interface FeedbackStatus {
    data object Idle : FeedbackStatus

    data object Submitting : FeedbackStatus

    /** Report captured; [issueUrl] is present only when the public issue was created. */
    data class Done(val issueUrl: String?) : FeedbackStatus

    /** Submission failed; [rateLimited] distinguishes the friendly cool-down message. */
    data class Failed(val rateLimited: Boolean) : FeedbackStatus
}

/**
 * Orchestrates a report submission. Pure Kotlin (Firebase-free) so it is
 * unit-testable with a fake repository. Maps [FeedbackRateLimitedException] to
 * a distinct [FeedbackStatus.Failed] so the UI can show a cool-down hint.
 */
class FeedbackCoordinator(
    private val repository: FeedbackRepository,
) {
    private val state = MutableStateFlow<FeedbackStatus>(FeedbackStatus.Idle)
    val status: StateFlow<FeedbackStatus> = state.asStateFlow()

    suspend fun submit(input: FeedbackReportInput) {
        if (state.value == FeedbackStatus.Submitting) return
        state.value = FeedbackStatus.Submitting
        try {
            val result = repository.report(input)
            state.value = FeedbackStatus.Done(result.issueUrl)
        } catch (cancellation: CancellationException) {
            state.value = FeedbackStatus.Idle
            throw cancellation
        } catch (rateLimited: FeedbackRateLimitedException) {
            state.value = FeedbackStatus.Failed(rateLimited = true)
        } catch (failure: Exception) {
            state.value = FeedbackStatus.Failed(rateLimited = false)
        }
    }

    /** Clears a terminal status (Done or Failed) so the form is fresh again. */
    fun reset() {
        if (state.value != FeedbackStatus.Submitting) {
            state.value = FeedbackStatus.Idle
        }
    }
}

/**
 * [FeedbackRepository] backed by the feedback-reportIssue callable
 * (europe-west1). Guarded ([createIfAvailable]): returns null when
 * google-services.json is absent (CI / local validation builds), mirroring the
 * rest of the Firebase wiring. Translates the resource-exhausted error code
 * into [FeedbackRateLimitedException]; every other failure propagates as-is.
 */
class FirebaseFeedbackRepository private constructor(
    private val functions: FirebaseFunctions,
) : FeedbackRepository {

    override suspend fun report(input: FeedbackReportInput): FeedbackSubmitResult {
        val data =
            buildMap<String, Any> {
                put("description", input.description)
                input.summary?.let { put("summary", it) }
                input.appVersion?.let { put("appVersion", it) }
                input.osVersion?.let { put("osVersion", it) }
                input.deviceModel?.let { put("deviceModel", it) }
            }
        return suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(CALLABLE)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        // HttpsCallableResult exposes getData(); the `data`
                        // property itself is private on the Java class.
                        @Suppress("UNCHECKED_CAST")
                        val payload = task.result?.getData() as? Map<String, Any?> ?: emptyMap()
                        // reportId is required for downstream UX/triage; a
                        // missing/blank id is a contract violation, not a
                        // success — fail rather than mask a backend regression.
                        val reportId = (payload["reportId"] as? String)?.takeIf { it.isNotBlank() }
                        if (reportId == null) {
                            continuation.resumeWithException(
                                IllegalStateException("$CALLABLE returned no reportId"),
                            )
                            return@addOnCompleteListener
                        }
                        continuation.resume(
                            FeedbackSubmitResult(
                                reportId = reportId,
                                issueUrl = payload["githubIssueUrl"] as? String,
                                created = payload["status"] == "created",
                            ),
                        )
                    } else {
                        val cause = task.exception
                        if (cause is FirebaseFunctionsException &&
                            cause.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED
                        ) {
                            continuation.resumeWithException(FeedbackRateLimitedException())
                        } else {
                            continuation.resumeWithException(
                                cause ?: IllegalStateException("$CALLABLE failed without a cause"),
                            )
                        }
                    }
                }
        }
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val CALLABLE = "feedback-reportIssue"

        fun createIfAvailable(context: Context): FeedbackRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseFeedbackRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}
