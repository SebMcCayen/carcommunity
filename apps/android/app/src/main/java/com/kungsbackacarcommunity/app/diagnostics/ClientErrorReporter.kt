package com.kungsbackacarcommunity.app.diagnostics

import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.BuildConfig

/**
 * Fire-and-forget sink for genuine RUNTIME errors surfaced in the app (e.g. the
 * Messages inbox listener failing). Each [report] call invokes the authenticated
 * `errors-reportClientError` callable, which (a) records the error in the admin
 * Audit Log and (b) triggers a DEDUPLICATED public GitHub issue — so a recurring
 * error never spams issues (dedup + a 30/hour server rate limit are the
 * backstop).
 *
 * Firebase-free interface so call sites are unit- and UI-testable with a fake or
 * the [NoopClientErrorReporter]. Implementations must NEVER throw: reporting an
 * error must not itself crash the screen that hit the error.
 *
 * NOT for pre-authentication failures (sign-in) — those use the public
 * diagnostics.submitReport path; this callable requires a signed-in caller.
 */
interface ClientErrorReporter {
    /**
     * @param feature stable screen/feature key, e.g. "messages.conversationList".
     * @param message app-generated error summary (never user free-text / PII).
     * @param code    optional stable code, e.g. a Firestore/Functions status name.
     */
    fun report(feature: String, message: String, code: String? = null)
}

/** No-op reporter used when Firebase is unavailable (CI / local validation builds). */
object NoopClientErrorReporter : ClientErrorReporter {
    override fun report(feature: String, message: String, code: String?) = Unit
}

/**
 * [ClientErrorReporter] backed by the `errors-reportClientError` callable
 * (europe-west1). Guarded ([createIfAvailable]): returns null when Firebase is
 * not configured, so callers fall back to [NoopClientErrorReporter].
 *
 * Best-effort and non-blocking: the callable is fired via the Task API and every
 * failure (network, rate limit, sign-out) is swallowed — a failure to REPORT an
 * error must never surface a second error to the user.
 */
class FirebaseClientErrorReporter private constructor(
    private val functions: FirebaseFunctions,
    private val appVersion: String,
    private val osVersion: String,
    private val deviceModel: String,
) : ClientErrorReporter {

    override fun report(feature: String, message: String, code: String?) {
        val data =
            buildMap<String, Any> {
                put("feature", feature)
                put("message", message)
                code?.let { put("code", it) }
                put("appVersion", appVersion)
                put("osVersion", osVersion)
                put("deviceModel", deviceModel)
                put("platform", PLATFORM)
            }
        try {
            functions
                .getHttpsCallable(CALLABLE)
                .call(data)
                // Best-effort telemetry: swallow the outcome (success or
                // failure). The backend rate-limits + dedups; nothing to do here.
                .addOnCompleteListener { /* no-op */ }
        } catch (_: Throwable) {
            // Never let error REPORTING throw into the caller.
        }
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val CALLABLE = "errors-reportClientError"
        private const val PLATFORM = "android"

        fun createIfAvailable(context: Context): ClientErrorReporter? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseClientErrorReporter(
                functions = FirebaseFunctions.getInstance(REGION),
                appVersion = BuildConfig.VERSION_NAME,
                osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        }
    }
}
