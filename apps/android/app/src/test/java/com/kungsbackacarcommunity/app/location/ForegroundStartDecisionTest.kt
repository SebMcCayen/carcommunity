package com.kungsbackacarcommunity.app.location

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for [ForegroundStartDecision.isBackgroundStartRefusal] — the pure
 * branch that decides whether a caught foreground-service-start
 * [IllegalStateException] is the recoverable "app was backgrounded" refusal
 * (record a pending start and retry on the next foreground) or a genuine fault
 * (rethrow). `Build.VERSION_CODES.S` is a compile-time constant (31), so this
 * exercises real values without a device.
 */
class ForegroundStartDecisionTest {

    @Test
    fun api31plus_specificRefusalType_isRecoverable() {
        // The exact ForegroundServiceStartNotAllowedException on a modern device
        // is the backgrounded-start refusal → pending + retry.
        assertTrue(
            ForegroundStartDecision.isBackgroundStartRefusal(
                sdkInt = Build.VERSION_CODES.S,
                isSpecificRefusalType = true,
            ),
        )
    }

    @Test
    fun api31plus_otherIllegalState_isNotRecoverable() {
        // A different ISE on API 31+ is a genuine fault → caller rethrows.
        assertFalse(
            ForegroundStartDecision.isBackgroundStartRefusal(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                isSpecificRefusalType = false,
            ),
        )
    }

    @Test
    fun preApi31_anyIllegalState_isRecoverable() {
        // API 26-30 has no dedicated type; the background-start restriction is the
        // only ISE startForegroundService raises there, so every ISE is treated as
        // the recoverable refusal.
        assertTrue(
            ForegroundStartDecision.isBackgroundStartRefusal(
                sdkInt = Build.VERSION_CODES.R,
                isSpecificRefusalType = false,
            ),
        )
    }
}
