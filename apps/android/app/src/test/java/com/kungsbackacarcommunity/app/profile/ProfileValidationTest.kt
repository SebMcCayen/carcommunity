package com.kungsbackacarcommunity.app.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidationTest {
    @Test
    fun `valid name and bio pass`() {
        val r = ProfileValidation.validate("Sebbe", "Volvo fan")
        assertTrue(r.isValid)
        assertNull(r.displayNameError)
        assertNull(r.bioError)
    }

    @Test
    fun `blank name is required`() {
        assertEquals(ProfileValidation.FieldError.REQUIRED, ProfileValidation.validate("  ", "").displayNameError)
    }

    @Test
    fun `over-long name and bio are flagged`() {
        val longName = "x".repeat(ProfileValidation.DISPLAY_NAME_MAX + 1)
        val longBio = "y".repeat(ProfileValidation.BIO_MAX + 1)
        val r = ProfileValidation.validate(longName, longBio)
        assertEquals(ProfileValidation.FieldError.TOO_LONG, r.displayNameError)
        assertEquals(ProfileValidation.FieldError.TOO_LONG, r.bioError)
    }

    @Test
    fun `empty bio is allowed`() {
        assertNull(ProfileValidation.validate("Sebbe", "").bioError)
    }
}
