package com.kungsbackacarcommunity.app.feedback

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
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
    /** GitHub issue number when the public issue was created; null otherwise. */
    val issueNumber: Int?,
    val created: Boolean,
)

/** Thrown when the per-user rate limit is hit (resource-exhausted). */
class FeedbackRateLimitedException : Exception()

/**
 * Thrown when the callable came back `unauthenticated`.
 *
 * THREE causes share that single code and they need completely different
 * fixes: an expired sign-in (user-fixable), a rejected App Check token (client
 * config), or a Cloud Run edge rejection because the callable's backing service
 * has no public invoker binding (a server-side outage that no rebuild can fix).
 * [reason] carries which one — see [FeedbackFailureDiagnosis].
 */
class FeedbackUnauthenticatedException(
    val reason: FeedbackFailureReason,
    cause: Throwable?,
) : Exception("feedback-reportIssue rejected the request as unauthenticated ($reason)", cause)

/** Files a "Report a problem" report via the callable (feedback.reportIssue). */
interface FeedbackRepository {
    suspend fun report(input: FeedbackReportInput): FeedbackSubmitResult
}

/** UI-facing status of a report submission. */
sealed interface FeedbackStatus {
    data object Idle : FeedbackStatus

    data object Submitting : FeedbackStatus

    /**
     * Report captured. [issueUrl]/[issueNumber] are present only when the public
     * issue was created; [summary] is the short title the user typed (may be
     * blank), carried through so the "thank you" window can echo it.
     */
    data class Done(
        val issueUrl: String?,
        val issueNumber: Int?,
        val summary: String?,
    ) : FeedbackStatus

    /** Submission failed; [reason] selects the message the user is shown. */
    data class Failed(val reason: FeedbackFailureReason) : FeedbackStatus
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
            state.value =
                FeedbackStatus.Done(
                    issueUrl = result.issueUrl,
                    issueNumber = result.issueNumber,
                    // The backend does not echo the summary; carry the submitted
                    // one so the confirmation window can show what was reported.
                    summary = input.summary?.takeIf { it.isNotBlank() },
                )
        } catch (cancellation: CancellationException) {
            state.value = FeedbackStatus.Idle
            throw cancellation
        } catch (rateLimited: FeedbackRateLimitedException) {
            state.value = FeedbackStatus.Failed(FeedbackFailureReason.RATE_LIMITED)
        } catch (unauthenticated: FeedbackUnauthenticatedException) {
            state.value = FeedbackStatus.Failed(unauthenticated.reason)
        } catch (failure: Exception) {
            state.value = FeedbackStatus.Failed(FeedbackFailureReason.UNKNOWN)
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
 * unauthenticated into [FeedbackUnauthenticatedException], the latter first
 * classified by [FeedbackFailureDiagnosis] and logged with its remediation —
 * and propagates every other failure as-is.
 *
 * [signedIn] is injected so the classification is exercisable without a
 * FirebaseAuth instance.
 */
class FirebaseFeedbackRepository private constructor(
    private val functions: FirebaseFunctions,
    private val signedIn: () -> Boolean,
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
                                // Callable numbers arrive as Double/Long via the
                                // JSON bridge; normalise to Int, null when absent.
                                issueNumber = (payload["githubIssueNumber"] as? Number)?.toInt(),
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
                                // `unauthenticated` is the one code that hides a
                                // server-side outage behind what looks like a
                                // client problem, so pin down WHICH cause it is
                                // and log the actual remediation. Otherwise the
                                // next occurrence is another blind guess between
                                // "register a debug token" and "the Cloud Run
                                // invoker binding is missing".
                                val envelope =
                                    FeedbackFailureDiagnosis.carriedServerErrorEnvelope(
                                        message = cause?.message,
                                        codeName = code.name,
                                    )
                                val reason =
                                    FeedbackFailureDiagnosis.classifyUnauthenticated(
                                        carriedServerErrorEnvelope = envelope,
                                        signedIn = signedIn(),
                                    )
                                Log.w(
                                    TAG,
                                    "$CALLABLE rejected as unauthenticated -> $reason " +
                                        "(serverErrorEnvelope=$envelope). " +
                                        FeedbackFailureDiagnosis.remediation(reason),
                                    cause,
                                )
                                continuation.resumeWithException(
                                    FeedbackUnauthenticatedException(reason, cause),
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

        // Aliases, not copies: FeedbackFailureDiagnosis owns these because its
        // remediation text quotes the service name and region, and that guidance
        // is only correct if it names the callable we actually invoke.
        private const val REGION = FeedbackFailureDiagnosis.REGION
        private const val CALLABLE = FeedbackFailureDiagnosis.CALLABLE

        fun createIfAvailable(context: Context): FeedbackRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseFeedbackRepository(
                functions = FirebaseFunctions.getInstance(REGION),
                signedIn = { FirebaseAuth.getInstance().currentUser != null },
            )
        }
    }
}
