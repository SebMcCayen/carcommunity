package com.kungsbackacarcommunity.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFormTest {
    @Test
    fun `canSubmit requires all three consents and a display name`() {
        val name = "Sebbe"
        assertTrue(OnboardingForm.canSubmit(true, true, true, name))
        assertFalse(OnboardingForm.canSubmit(false, true, true, name))
        assertFalse(OnboardingForm.canSubmit(true, false, true, name))
        assertFalse(OnboardingForm.canSubmit(true, true, false, name))
    }

    @Test
    fun `canSubmit requires a non-blank valid display name`() {
        assertFalse(OnboardingForm.canSubmit(true, true, true, ""))
        assertFalse(OnboardingForm.canSubmit(true, true, true, "   "))
        assertFalse(
            OnboardingForm.canSubmit(true, true, true, "x".repeat(OnboardingForm.DISPLAY_NAME_MAX_LENGTH + 1)),
        )
        assertTrue(OnboardingForm.canSubmit(true, true, true, "  Sebbe  "))
    }

    @Test
    fun `isDisplayNameValid checks non-blank and length`() {
        assertTrue(OnboardingForm.isDisplayNameValid("Sebbe"))
        assertFalse(OnboardingForm.isDisplayNameValid(""))
        assertFalse(OnboardingForm.isDisplayNameValid("   "))
        assertTrue(OnboardingForm.isDisplayNameValid("x".repeat(OnboardingForm.DISPLAY_NAME_MAX_LENGTH)))
        assertFalse(OnboardingForm.isDisplayNameValid("x".repeat(OnboardingForm.DISPLAY_NAME_MAX_LENGTH + 1)))
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
