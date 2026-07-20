package com.kungsbackacarcommunity.app.auth

import com.kungsbackacarcommunity.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which message the sign-in screen shows for each failure.
 *
 * The gap this guards: a device with no Google account rendered the SAME
 * generic "sign-in failed, please try again" as every other failure, which was
 * both a dead end for the user and indistinguishable from a real fault.
 *
 * These assert on the resource actually rendered by [SignInScreen] (the
 * composable calls [signInFailureMessageRes] directly), and the
 * `assertNotEquals` against the generic string is the load-bearing one — an
 * implementation that regressed to the shared message would satisfy a
 * "shows some message" assertion but fails here.
 */
class SignInFailureMessageTest {

    @Test
    fun `no google account gets its own actionable message, not the generic one`() {
        val message = signInFailureMessageRes(SignInFailure.NO_GOOGLE_ACCOUNT)

        assertEquals(
            "The missing-account case must show the add-a-Google-account guidance",
            R.string.auth_errorNoGoogleAccount,
            message,
        )
        // The actual regression this file exists to catch.
        assertNotEquals(
            "A device with no Google account must NOT fall back to the generic " +
                "dead-end error — that is the whole bug being fixed",
            R.string.auth_errorGeneric,
            message,
        )
    }

    @Test
    fun `a genuine fault still shows the generic message`() {
        // The control case: the new branch must not swallow everything else.
        assertEquals(
            R.string.auth_errorGeneric,
            signInFailureMessageRes(SignInFailure.GENERIC),
        )
        assertEquals(
            R.string.auth_platformUnsupported,
            signInFailureMessageRes(SignInFailure.UNAVAILABLE),
        )
    }

    @Test
    fun `every failure reason maps to a distinct message`() {
        // Guards the general shape: if a future reason is added and quietly
        // pointed at an existing string, this fails rather than shipping two
        // states the user cannot tell apart.
        val byReason = SignInFailure.entries.associateWith { signInFailureMessageRes(it) }

        assertEquals(
            "Two failure reasons share a message: $byReason",
            byReason.size,
            byReason.values.toSet().size,
        )
    }
}
