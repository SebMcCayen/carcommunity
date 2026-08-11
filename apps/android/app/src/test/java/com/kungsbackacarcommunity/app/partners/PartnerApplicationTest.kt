package com.kungsbackacarcommunity.app.partners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PartnerApplicationTest {

    private fun valid() =
        PartnerApplicationForm(
            companyName = "Bilverkstan",
            category = PartnerCategory.WORKSHOP,
            contactName = "Ada",
            contactEmail = "ada@example.com",
            contactPhone = "010-123",
            websiteUrl = "https://example.com",
            message = "Hej",
        )

    @Test
    fun `a complete form validates and maps to an input`() {
        assertNull(PartnerApplications.validate(valid()))
        val input = PartnerApplications.toInput(valid())
        assertNotNull(input)
        assertEquals("Bilverkstan", input!!.companyName)
        assertEquals(PartnerCategory.WORKSHOP, input.category)
        assertEquals("ada@example.com", input.contactEmail)
    }

    @Test
    fun `required fields and email are validated in order`() {
        assertEquals(PartnerApplicationError.COMPANY_NAME_REQUIRED, PartnerApplications.validate(valid().copy(companyName = " ")))
        assertEquals(PartnerApplicationError.CATEGORY_REQUIRED, PartnerApplications.validate(valid().copy(category = null)))
        assertEquals(PartnerApplicationError.CONTACT_NAME_REQUIRED, PartnerApplications.validate(valid().copy(contactName = "")))
        assertEquals(PartnerApplicationError.CONTACT_EMAIL_INVALID, PartnerApplications.validate(valid().copy(contactEmail = "nope")))
        assertEquals(PartnerApplicationError.CONTACT_EMAIL_INVALID, PartnerApplications.validate(valid().copy(contactEmail = "a@b")))
    }

    @Test
    fun `blank optional fields map to null`() {
        val input = PartnerApplications.toInput(valid().copy(contactPhone = "", websiteUrl = "  ", message = ""))
        assertNull(input!!.contactPhone)
        assertNull(input.websiteUrl)
        assertNull(input.message)
    }

    @Test
    fun `toInput prepends https to a scheme-less website`() {
        val input = PartnerApplications.toInput(valid().copy(websiteUrl = "www.foretag.se"))
        assertEquals("https://www.foretag.se", input!!.websiteUrl)
    }

    @Test
    fun `toInput leaves an http or https website untouched`() {
        assertEquals(
            "https://example.com",
            PartnerApplications.toInput(valid().copy(websiteUrl = "https://example.com"))!!.websiteUrl,
        )
        assertEquals(
            "http://example.com",
            PartnerApplications.toInput(valid().copy(websiteUrl = "http://example.com"))!!.websiteUrl,
        )
        // Scheme match is case-insensitive.
        assertEquals(
            "HTTPS://example.com",
            PartnerApplications.toInput(valid().copy(websiteUrl = "HTTPS://example.com"))!!.websiteUrl,
        )
    }

    @Test
    fun `toInput keeps an empty website empty (null), not https`() {
        assertNull(PartnerApplications.toInput(valid().copy(websiteUrl = ""))!!.websiteUrl)
        assertNull(PartnerApplications.toInput(valid().copy(websiteUrl = "   "))!!.websiteUrl)
    }
}
