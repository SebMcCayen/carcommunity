package com.kungsbackacarcommunity.app.diagnostics

import com.kungsbackacarcommunity.app.auth.SignInFailureDetails
import com.kungsbackacarcommunity.app.auth.SignInStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the diagnostics report shape emitted for a sign-in failure. The backend
 * extractor (signInIssues-core.extractSignInFailureReport) is STRICT: it rejects
 * the report unless `errorCode` is a class-name token and `safeMessage` equals
 * `Sign-in failed: <errorCode>` byte-for-byte, so these assertions guard the
 * contract. The enriched context (step / cause chain / status) must ride in
 * metadata (admin-only) and NEVER leak PII.
 */
class DiagnosticsSignInFailureReporterTest {

    private fun details(
        errorType: String = "NoCredentialException",
        causeChain: List<String> = listOf("SignInFailedException", "NoCredentialException"),
        step: SignInStep = SignInStep.CREDENTIAL_FETCH,
        statusCode: String? = "androidx.credentials.TYPE_NO_CREDENTIAL",
    ) = SignInFailureDetails(errorType, causeChain, step, statusCode)

    private fun capture(
        reporterDetails: SignInFailureDetails,
        deviceModel: String? = "Google Pixel 8",
    ): DiagnosticsReport {
        val captured = mutableListOf<DiagnosticsReport>()
        DiagnosticsSignInFailureReporter(
            reporter = { captured.add(it) },
            appVersion = "0.1.0",
            buildNumber = "4",
            osVersion = "Android 14 (API 34)",
            deviceModel = deviceModel,
        ).reportSignInFailure(reporterDetails)
        return captured.single()
    }

    @Test
    fun `emits the exact backend-gate shape errorCode plus Sign-in failed message`() {
        val report = capture(details(errorType = "NoCredentialException"))
        assertEquals(DiagnosticsSeverity.ERROR, report.severity)
        assertEquals(DiagnosticsFeatureArea.SIGN_IN, report.featureArea)
        assertEquals("NoCredentialException", report.errorCode)
        // Byte-for-byte `Sign-in failed: <errorCode>` — the backend rejects anything else.
        assertEquals("Sign-in failed: NoCredentialException", report.safeMessage)
    }

    @Test
    fun `carries the enriched context as metadata scalars`() {
        val report = capture(details())
        val metadata = report.metadata!!
        assertEquals("Google Pixel 8", metadata["deviceModel"])
        assertEquals("credential_fetch", metadata["signInStep"])
        assertEquals(
            "SignInFailedException -> NoCredentialException",
            metadata["causeChain"],
        )
        assertEquals("androidx.credentials.TYPE_NO_CREDENTIAL", metadata["errorStatus"])
    }

    @Test
    fun `metadata keys survive the backend key-based sanitizer (no blocked substrings)`() {
        val report = capture(details())
        val blocked = listOf("token", "secret", "password", "credential", "auth", "stack", "trace")
        for (key in report.metadata!!.keys) {
            val lower = key.lowercase()
            for (needle in blocked) {
                assertFalse("metadata key `$key` must survive sanitizeMetadata", lower.contains(needle))
            }
        }
    }

    @Test
    fun `omits absent status and device model but always includes the step`() {
        val report =
            capture(details(statusCode = null), deviceModel = null)
        val metadata = report.metadata!!
        assertFalse(metadata.containsKey("errorStatus"))
        assertFalse(metadata.containsKey("deviceModel"))
        assertEquals("credential_fetch", metadata["signInStep"])
    }

    @Test
    fun `reports the firebase exchange step wire name`() {
        val report = capture(details(step = SignInStep.FIREBASE_EXCHANGE))
        assertEquals("firebase_exchange", report.metadata!!["signInStep"])
    }

    @Test
    fun `never crashes when the underlying reporter throws`() {
        val throwing = DiagnosticsReporter { throw IllegalStateException("sink down") }
        val reporter =
            DiagnosticsSignInFailureReporter(throwing, null, null, null, null)
        // Must swallow the failure — diagnostics can never mask the sign-in error.
        reporter.reportSignInFailure(details())
// Intentionally no assertion: this test passes if no exception is thrown.
    }

    @Test
    fun `an unwrapped failure still produces a valid single-element chain`() {
        val report =
            capture(
                details(
                    errorType = "IllegalStateException",
                    causeChain = listOf("IllegalStateException"),
                    statusCode = null,
                ),
            )
        assertEquals("Sign-in failed: IllegalStateException", report.safeMessage)
        assertEquals("IllegalStateException", report.metadata!!["causeChain"])
        assertTrue(report.metadata!!.containsKey("signInStep"))
    }
}
