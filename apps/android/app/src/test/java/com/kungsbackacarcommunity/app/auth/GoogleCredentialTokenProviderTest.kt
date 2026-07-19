package com.kungsbackacarcommunity.app.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cancellation-vs-fault decision for issue #457.
 *
 * [GoogleCredentialTokenProvider.toSignInException] is the single point where a
 * Credential Manager failure becomes either a reportable fault or a silently
 * dropped user cancellation, so it is asserted directly. The exception classes
 * used here are plain JVM types from androidx.credentials — no Android framework
 * is touched, so this runs as an ordinary unit test.
 */
class GoogleCredentialTokenProviderTest {

    @Test
    fun `user cancellation maps to the non-reportable cancelled exception`() {
        val mapped =
            GoogleCredentialTokenProvider.toSignInException(
                GetCredentialCancellationException("user dismissed the sheet"),
            )

        assertTrue(
            "A cancellation must map to SignInCancelledException so the coordinator " +
                "drops it, but got ${mapped.javaClass.simpleName}",
            mapped is SignInCancelledException,
        )
    }

    @Test
    fun `genuine credential faults stay reportable and keep their diagnostic code`() {
        // Deliberately NOT filtered: each of these is something an admin needs to
        // see. Over-filtering blinds the diagnostics pipeline.
        val faults =
            listOf(
                GetCredentialProviderConfigurationException("misconfigured"),
                GetCredentialUnknownException("unknown"),
                GetCredentialUnsupportedException("unsupported device"),
                GetCredentialInterruptedException("interrupted"),
                NoCredentialException("no google account on device"),
            )

        for (fault in faults) {
            val mapped = GoogleCredentialTokenProvider.toSignInException(fault)

            assertTrue(
                "${fault.javaClass.simpleName} must remain reportable, but got " +
                    mapped.javaClass.simpleName,
                mapped is SignInFailedException,
            )
            assertEquals(
                "${fault.javaClass.simpleName} must carry its PII-safe status code",
                fault.type,
                (mapped as SignInFailedException).diagnosticCode,
            )
            assertEquals(
                "the concrete subtype must survive as the cause for the report's errorType",
                fault,
                mapped.cause,
            )
        }
    }
}
