package com.kungsbackacarcommunity.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFormTest {
    @Test
    fun `canSubmit requires all three consents`() {
        assertTrue(OnboardingForm.canSubmit(true, true, true))
        assertFalse(OnboardingForm.canSubmit(false, true, true))
        assertFalse(OnboardingForm.canSubmit(true, false, true))
        assertFalse(OnboardingForm.canSubmit(true, true, false))
    }

    @Test
    fun `normalizedDisplayName trims and nulls blanks`() {
        assertEquals("Sebbe", OnboardingForm.normalizedDisplayName("  Sebbe "))
        assertNull(OnboardingForm.normalizedDisplayName(""))
        assertNull(OnboardingForm.normalizedDisplayName("   "))
    }

    @Test
    fun `normalizedDisplayName rejects over-long names`() {
        val long = "x".repeat(OnboardingForm.DISPLAY_NAME_MAX_LENGTH + 1)
        assertNull(OnboardingForm.normalizedDisplayName(long))
        assertTrue(OnboardingForm.isDisplayNameTooLong(long))
        assertFalse(OnboardingForm.isDisplayNameTooLong("Sebbe"))
    }
}
