package com.kungsbackacarcommunity.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure ask-once policy for the battery-optimization exemption (#849):
 * ask only when a drive is recording, the app is not already exempt, and it has
 * never been asked before.
 */
class BatteryOptimizationGateTest {

    @Test
    fun `prompts on the first recording drive when not exempt and not yet asked`() {
        assertTrue(
            BatteryOptimizationGate.shouldPrompt(
                isRecordingDrive = true,
                isIgnoringBatteryOptimizations = false,
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `never prompts when no drive is recording`() {
        assertFalse(
            BatteryOptimizationGate.shouldPrompt(
                isRecordingDrive = false,
                isIgnoringBatteryOptimizations = false,
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `never prompts when the app is already exempt`() {
        assertFalse(
            BatteryOptimizationGate.shouldPrompt(
                isRecordingDrive = true,
                isIgnoringBatteryOptimizations = true,
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `never prompts twice`() {
        assertFalse(
            BatteryOptimizationGate.shouldPrompt(
                isRecordingDrive = true,
                isIgnoringBatteryOptimizations = false,
                alreadyAsked = true,
            ),
        )
    }
}
