package com.kungsbackacarcommunity.app.diagnostics

import com.kungsbackacarcommunity.app.auth.SignInFailureReporter

/**
 * Bridges a sign-in failure into the diagnostics pipeline: adapts a
 * [SignInFailureReporter] onto the existing [DiagnosticsReporter] so a
 * pre-authentication Google Sign-In failure is submitted to the PUBLIC,
 * unauthenticated `diagnostics-submitReport` callable (the only telemetry path
 * that works before auth). The backend diagnostics-onSignInFailure trigger then
 * files a deduplicated public GitHub issue.
 *
 * Privacy: the report carries ONLY the sanitized error type (the failing
 * exception's simple class name, supplied by [SignInCoordinator]) plus bounded
 * client context and the device model. NEVER the exception message,
 * credentials, tokens, email, or any PII. The device model rides in metadata,
 * which the backend independently re-sanitizes.
 *
 * Fire-and-forget: [reportSignInFailure] never throws (the underlying reporter
 * already swallows failures; this guards against any that slip through) and
 * returns promptly.
 */
class DiagnosticsSignInFailureReporter(
    private val reporter: DiagnosticsReporter,
    private val appVersion: String?,
    private val buildNumber: String?,
    private val osVersion: String?,
    private val deviceModel: String?,
) : SignInFailureReporter {

    override fun reportSignInFailure(errorType: String) {
        try {
            reporter.report(
                DiagnosticsReport(
                    severity = DiagnosticsSeverity.ERROR,
                    featureArea = DiagnosticsFeatureArea.SIGN_IN,
                    safeMessage = "Sign-in failed: $errorType",
                    errorCode = errorType,
                    appVersion = appVersion,
                    buildNumber = buildNumber,
                    osVersion = osVersion,
                    metadata = deviceModel?.let { mapOf("deviceModel" to it) },
                ),
            )
        } catch (error: Exception) {
            // Diagnostics must never crash the app or mask the sign-in failure.
        }
    }
}
