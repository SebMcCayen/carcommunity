package com.kungsbackacarcommunity.app.diagnostics

import com.kungsbackacarcommunity.app.auth.SignInFailureDetails
import com.kungsbackacarcommunity.app.auth.SignInFailureReporter

/**
 * Bridges a sign-in failure into the diagnostics pipeline: adapts a
 * [SignInFailureReporter] onto the existing [DiagnosticsReporter] so a
 * pre-authentication Google Sign-In failure is submitted to the PUBLIC,
 * unauthenticated `diagnostics-submitReport` callable (the only telemetry path
 * that works before auth — and, deliberately, one that no longer requires an App
 * Check token, so it still reports on devices that cannot yet attest). The
 * backend diagnostics-onSignInFailure trigger then files a deduplicated public
 * GitHub issue.
 *
 * Privacy: the report carries ONLY sanitized class names and stable status
 * constants — the ROOT-CAUSE exception type as [DiagnosticsReport.errorCode]
 * (and mirrored into `safeMessage` in the exact `Sign-in failed: <type>` shape
 * the backend gate expects), plus the failing step, the cause-chain class names,
 * and any Credential Manager / Firebase status code as sanitized metadata
 * scalars, plus the device model. NEVER the exception message, credentials,
 * tokens, email, or any PII. Metadata is independently re-sanitized server-side,
 * and only [DiagnosticsReport.errorCode] (never metadata) is echoed into the
 * PUBLIC GitHub issue — the enriched context stays in the admin-only report.
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

    override fun reportSignInFailure(details: SignInFailureDetails) {
        try {
            reporter.report(
                DiagnosticsReport(
                    severity = DiagnosticsSeverity.ERROR,
                    featureArea = DiagnosticsFeatureArea.SIGN_IN,
                    // Must stay byte-for-byte `Sign-in failed: <errorCode>` with
                    // errorCode == the class-name token, or the backend extractor
                    // rejects the report (see signInIssues-core extractSignInFailureReport).
                    safeMessage = "Sign-in failed: ${details.errorType}",
                    errorCode = details.errorType,
                    appVersion = appVersion,
                    buildNumber = buildNumber,
                    osVersion = osVersion,
                    metadata = buildMetadata(details),
                ),
            )
        } catch (error: Exception) {
            // Diagnostics must never crash the app or mask the sign-in failure.
        }
    }

    /**
     * Enriched, PII-safe context for the ADMIN-only report (never surfaced in the
     * public issue). Keys are chosen to survive the backend's key-based metadata
     * sanitizer (no `token`/`secret`/`credential`/`auth`/`stack`/`trace`
     * substrings). Values are class names / stable constants — no PII.
     */
    private fun buildMetadata(details: SignInFailureDetails): Map<String, Any?>? =
        buildMap {
            deviceModel?.let { put("deviceModel", it) }
            put("signInStep", details.step.wireName)
            if (details.causeChain.isNotEmpty()) {
                // Outermost wrapper → root cause, e.g.
                // "SignInFailedException -> NoCredentialException".
                put("causeChain", details.causeChain.joinToString(" -> "))
            }
            details.statusCode?.let { put("errorStatus", it) }
        }.takeIf { it.isNotEmpty() }
}
