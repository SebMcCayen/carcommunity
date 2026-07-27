package com.kungsbackacarcommunity.app.feedback

import android.content.Context
import android.util.Log
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

/**
 * Thrown when the callable rejected the request as `unauthenticated`.
 *
 * Two distinct causes share this code, and neither used to be distinguishable
 * from a generic failure: a missing/expired sign-in, or a rejected App Check
 * token. On debug builds the latter is the usual one — see docs/app-check.md
 * for the stable-debug-token setup that prevents it.
 */
class FeedbackUnauthenticatedException(cause: Throwable?) : Exception(
    "feedback-reportIssue rejected the request as unauthenticated " +
        "(sign-in expired, or App Check rejected the token)",
    cause,
)

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
 * rest of the Firebase wiring. Translates two callable error codes into typed
 * exceptions — resource-exhausted into [FeedbackRateLimitedException] and
 * unauthenticated into [FeedbackUnauthenticatedException] (also logged, since
 * the coordinator renders it as a generic failure) — and propagates every other
 * failure as-is.
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
                        val code = (cause as? FirebaseFunctionsException)?.code
                        when (code) {
                            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                                continuation.resumeWithException(FeedbackRateLimitedException())

                            FirebaseFunctionsException.Code.UNAUTHENTICATED -> {
                                // The coordinator collapses every non-rate-limit
                                // failure into the same generic UI state, so
                                // without this the single most likely cause of a
                                // "reporting an issue errors" report — App Check
                                // rejecting the token — leaves no trace at all.
                                Log.w(
                                    TAG,
                                    "$CALLABLE rejected as unauthenticated; if this is a debug " +
                                        "build, check the App Check debug token (docs/app-check.md)",
                                    cause,
                                )
                                continuation.resumeWithException(
                                    FeedbackUnauthenticatedException(cause),
                                )
                            }

                            else ->
                                continuation.resumeWithException(
                                    cause
                                        ?: IllegalStateException("$CALLABLE failed without a cause"),
                                )
                        }
                    }
                }
        }
    }

    companion object {
        private const val TAG = "FeedbackRepository"
        private const val REGION = "europe-west1"
        private const val CALLABLE = "feedback-reportIssue"

        fun createIfAvailable(context: Context): FeedbackRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseFeedbackRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}
