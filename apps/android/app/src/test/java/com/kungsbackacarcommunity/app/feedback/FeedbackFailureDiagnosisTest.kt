package com.kungsbackacarcommunity.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackFailureDiagnosisTest {

    @Test
    fun `a body with no error envelope is the SDK falling back to the code name`() {
        // What the SDK produces for a Cloud Run edge 401: the plain-text body
        // fails JSONObject parsing, the JSONException is swallowed, and the
        // message is left at its default of code.name.
        assertFalse(
            FeedbackFailureDiagnosis.carriedServerErrorEnvelope(
                message = "UNAUTHENTICATED",
                codeName = "UNAUTHENTICATED",
            ),
        )
    }

    @Test
    fun `a message from the function's error envelope is recognised`() {
        assertTrue(
            FeedbackFailureDiagnosis.carriedServerErrorEnvelope(
                message = "Sign in to continue.",
                codeName = "UNAUTHENTICATED",
            ),
        )
        assertTrue(
            FeedbackFailureDiagnosis.carriedServerErrorEnvelope(
                message = "Unauthenticated",
                codeName = "UNAUTHENTICATED",
            ),
        )
    }

    @Test
    fun `a null message is treated as no envelope`() {
        assertFalse(
            FeedbackFailureDiagnosis.carriedServerErrorEnvelope(
                message = null,
                codeName = "UNAUTHENTICATED",
            ),
        )
    }

    @Test
    fun `no envelope means the function never ran, whatever the sign-in state`() {
        assertEquals(
            FeedbackFailureReason.SERVICE_NOT_INVOCABLE,
            FeedbackFailureDiagnosis.classifyUnauthenticated(
                carriedServerErrorEnvelope = false,
                signedIn = true,
            ),
        )
        assertEquals(
            FeedbackFailureReason.SERVICE_NOT_INVOCABLE,
            FeedbackFailureDiagnosis.classifyUnauthenticated(
                carriedServerErrorEnvelope = false,
                signedIn = false,
            ),
        )
    }

    @Test
    fun `an enveloped rejection with no signed-in user is a lost session`() {
        assertEquals(
            FeedbackFailureReason.SIGNED_OUT,
            FeedbackFailureDiagnosis.classifyUnauthenticated(
                carriedServerErrorEnvelope = true,
                signedIn = false,
            ),
        )
    }

    @Test
    fun `an enveloped rejection of a signed-in caller is the App Check gate`() {
        assertEquals(
            FeedbackFailureReason.APP_CHECK_REJECTED,
            FeedbackFailureDiagnosis.classifyUnauthenticated(
                carriedServerErrorEnvelope = true,
                signedIn = true,
            ),
        )
    }

    @Test
    fun `every reason has non-blank remediation guidance`() {
        for (reason in FeedbackFailureReason.entries) {
            assertTrue(
                "no remediation for $reason",
                FeedbackFailureDiagnosis.remediation(reason).isNotBlank(),
            )
        }
    }

    @Test
    fun `the invoker-binding remediation names the service, the role and the no-redeploy trap`() {
        val text = FeedbackFailureDiagnosis.remediation(FeedbackFailureReason.SERVICE_NOT_INVOCABLE)
        assertTrue(text.contains(FeedbackFailureDiagnosis.RUN_SERVICE))
        assertTrue(text.contains(FeedbackFailureDiagnosis.RUN_REGION))
        assertTrue(text.contains("roles/run.invoker"))
        assertTrue(text.contains("allUsers"))
        // The trap that made this outage persist for weeks: re-deploying does
        // not re-apply the binding, so the guidance must say so.
        assertTrue(text.contains("CREATED"))
    }

    @Test
    fun `the App Check remediation names both halves of the debug-token setup`() {
        val text = FeedbackFailureDiagnosis.remediation(FeedbackFailureReason.APP_CHECK_REJECTED)
        assertTrue(text.contains("appcheck.debugToken"))
        assertTrue(text.contains("local.properties"))
        assertTrue(text.contains("Manage debug tokens"))
    }
}
