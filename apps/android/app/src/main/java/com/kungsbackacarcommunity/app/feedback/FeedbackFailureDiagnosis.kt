package com.kungsbackacarcommunity.app.feedback

/**
 * Why a "Report a problem" submission failed, at the granularity the UI needs
 * and — more importantly — at the granularity a developer reading logcat needs
 * to tell an *operational outage* apart from a *client misconfiguration*.
 *
 * Historically every non-rate-limit failure collapsed into one generic message,
 * which made the flow undiagnosable from the device: a missing Cloud Run
 * invoker binding (server-side, affects everyone, no rebuild will fix it) and
 * an unregistered App Check debug token (client-side, affects only that
 * install) looked exactly the same to the user *and* in the log.
 */
enum class FeedbackFailureReason {
    /** Per-user cool-down: the callable returned `resource-exhausted`. */
    RATE_LIMITED,

    /**
     * The callable ran, and rejected us because there is no signed-in user.
     * The only reason here the user can act on themselves.
     */
    SIGNED_OUT,

    /**
     * The callable ran and rejected the App Check attestation
     * (`enforceAppCheck`). On debug builds this is almost always an
     * unregistered debug token — see docs/app-check.md.
     */
    APP_CHECK_REJECTED,

    /**
     * The request never reached the function at all: Cloud Run refused it at
     * the edge. For a v2 callable that means the backing Cloud Run service is
     * missing its `allUsers -> roles/run.invoker` binding. Server-side, and no
     * client change or rebuild can fix it.
     */
    SERVICE_NOT_INVOCABLE,

    /** Anything else — network, backend 5xx, contract violation. */
    UNKNOWN,
}

/**
 * Pure (Firebase-free, unit-testable) classification of an `unauthenticated`
 * callable failure into the cause that actually explains it.
 *
 * ## How an edge rejection is distinguished from a function rejection
 *
 * Both arrive as HTTP 401, which `FirebaseFunctionsException.Code.fromHttpStatus`
 * maps to `UNAUTHENTICATED` either way — so the *code* cannot separate them.
 * The response **body** can, because only one of the two is produced by our
 * function:
 *
 *  * A rejection raised inside the function (`HttpsError('unauthenticated', …)`
 *    from `requireActiveActor`, or the `enforceAppCheck` gate) is serialised by
 *    firebase-functions as a JSON envelope — `{"error":{"status":…,"message":…}}`.
 *    The SDK parses it and uses that `message` as the exception message.
 *  * A rejection at the Cloud Run edge never reaches our code, so the body is
 *    Cloud Run's own plain text ("The request was not authorized to invoke this
 *    service…"). `FirebaseFunctionsException.Companion.fromResponse` builds it
 *    with `new JSONObject(body)`, which throws `JSONException`; that exception
 *    is **caught and swallowed**, leaving the message at its initialised
 *    default of `code.name` (verified against the bytecode of
 *    `firebase-functions:22.1.1` — the `JSONException` handler, unlike the
 *    `IllegalArgumentException` one, does not even downgrade the code to
 *    `INTERNAL`).
 *
 * So `message == code.name` means "the response carried no error envelope from
 * our function", i.e. the function never ran. That is the signal used below.
 * It degrades safely: if a future SDK starts reporting a different message for
 * edge rejections, this misreads as [FeedbackFailureReason.APP_CHECK_REJECTED]
 * and the log still names both candidates.
 */
object FeedbackFailureDiagnosis {

    /** Cloud Run service backing the `feedback.reportIssue` callable. */
    const val RUN_SERVICE = "feedback-reportissue"

    /** Region the callable is deployed to. */
    const val RUN_REGION = "europe-west1"

    /**
     * True when the 401 body carried a firebase-functions error envelope, i.e.
     * the rejection was raised *by the function*. See the class doc.
     */
    fun carriedServerErrorEnvelope(message: String?, codeName: String): Boolean =
        message != null && message != codeName

    /**
     * @param carriedServerErrorEnvelope from [carriedServerErrorEnvelope].
     * @param signedIn whether Firebase Auth currently has a user. Our callable
     *   raises `unauthenticated` for exactly one reason once it is running —
     *   no authenticated caller — so a *signed-in* caller that still gets an
     *   enveloped `unauthenticated` was stopped by the App Check gate.
     */
    fun classifyUnauthenticated(
        carriedServerErrorEnvelope: Boolean,
        signedIn: Boolean,
    ): FeedbackFailureReason =
        when {
            !carriedServerErrorEnvelope -> FeedbackFailureReason.SERVICE_NOT_INVOCABLE
            !signedIn -> FeedbackFailureReason.SIGNED_OUT
            else -> FeedbackFailureReason.APP_CHECK_REJECTED
        }

    /**
     * The line a developer should be able to act on straight from logcat,
     * without re-deriving this investigation.
     */
    fun remediation(reason: FeedbackFailureReason): String =
        when (reason) {
            FeedbackFailureReason.SERVICE_NOT_INVOCABLE ->
                "The request was refused at the Cloud Run edge — the function never ran, so " +
                    "no rebuild, reinstall or App Check change can fix it. The v2 callable's " +
                    "backing service is missing its public invoker binding. Note that " +
                    "firebase-tools only applies that binding when the function is CREATED, " +
                    "never on a later update, so re-deploying will NOT restore it. Fix once " +
                    "with:\n" +
                    "  gcloud run services add-iam-policy-binding $RUN_SERVICE \\\n" +
                    "    --region=$RUN_REGION --member=allUsers --role=roles/run.invoker \\\n" +
                    "    --project=<firebase-project-id>\n" +
                    "(Safe for an onCall callable: it only lets a request reach the service; " +
                    "Firebase Auth and enforceAppCheck still authorize it. See docs/app-check.md.)"

            FeedbackFailureReason.APP_CHECK_REJECTED ->
                "The function ran and rejected the App Check attestation. On a DEBUG build " +
                    "this is almost always an unregistered debug token: set " +
                    "`appcheck.debugToken=<uuid>` in apps/android/local.properties AND register " +
                    "the same uuid in Firebase console -> App Check -> Manage debug tokens. " +
                    "See docs/app-check.md."

            FeedbackFailureReason.SIGNED_OUT ->
                "No signed-in Firebase user — the session expired. Sign in again."

            FeedbackFailureReason.RATE_LIMITED ->
                "Per-user cool-down hit (5 reports/hour). Expected behaviour, not a fault."

            FeedbackFailureReason.UNKNOWN ->
                "Unclassified failure — see the attached cause."
        }
}
