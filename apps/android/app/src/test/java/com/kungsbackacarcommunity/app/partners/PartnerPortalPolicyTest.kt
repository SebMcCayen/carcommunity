package com.kungsbackacarcommunity.app.partners

import org.junit.Assert.*
import org.junit.Test

class PartnerPortalPolicyTest {
    @Test fun `only active companies qualify for the business workspace`() {
        val active = PartnerWorkspaceCompany("active", "Partner", "", "redeemer", "active", null, null, null)
        val inactive = active.copy(companyId = "old", role = "manager", status = "inactive")
        val application = PartnerApplicationSummary("app", "approved", null, emptyMap())
        assertTrue(PartnerWorkspace(listOf(inactive), application).activeCompanies.isEmpty())
        assertEquals(listOf(active), PartnerWorkspace(listOf(inactive, active), null).activeCompanies)
    }

    @Test fun `QR countdown expires using elapsed time and never extends beyond sixty seconds`() {
        assertEquals(60, partnerQrSecondsRemaining(160_000, 100_000))
        assertEquals(1, partnerQrSecondsRemaining(160_000, 159_000))
        assertEquals(0, partnerQrSecondsRemaining(160_000, 160_000))
        assertEquals(0, partnerQrSecondsRemaining(160_000, 161_000))
        assertEquals(60, partnerQrSecondsRemaining(160_000, 0))
    }

    @Test fun `unlimited offer preserves null capacity and calendar end is exclusive next day`() {
        val fields = partnerOfferFields("Offer", "Teaser", "Description", "Terms", "", "", "7", "2026-11-20", "2026-11-21")
        assertNull(fields["capacity"])
        assertNull(fields["percentageDiscount"])
        assertEquals("2026-11-20T00:00:00Z", fields["availableFrom"])
        assertEquals("2026-11-22T00:00:00Z", fields["availableUntil"])
        assertEquals(7, fields["reservationDays"])
    }

    @Test fun `invalid quantities and reversed windows are rejected before sending`() {
        for ((capacity, days, percent) in listOf(Triple("0", "7", ""), Triple("3", "31", ""), Triple("3", "7", "101"), Triple("abc", "7", ""))) {
            assertThrows(IllegalArgumentException::class.java) { partnerOfferFields("Offer", "Teaser", "Description", "Terms", percent, capacity, days, "", "") }
        }
        assertThrows(IllegalArgumentException::class.java) { partnerOfferFields("Offer", "Teaser", "Description", "Terms", "", "3", "7", "2026-12-10", "2026-12-01") }
    }

    @Test fun `editing a draft keeps its inclusive final date without extending the campaign`() {
        val exclusiveEnd = "2026-11-22T00:00:00Z"
        val displayedEnd = partnerOfferEndDate(exclusiveEnd)
        assertEquals("2026-11-21", displayedEnd)
        val saved = partnerOfferFields("Offer", "Teaser", "Description", "Terms", "", "3", "7", "2026-11-20", displayedEnd)
        assertEquals(exclusiveEnd, saved["availableUntil"])
        assertEquals("", partnerOfferEndDate(null))
    }

    @Test fun `customer projection parses remaining capacity without requiring private counts`() {
        val result = parseManagedOffer(mapOf("offerId" to "offer", "companyId" to "partner", "capacity" to 3L, "remainingCount" to 2L, "version" to 9L, "reservationsPaused" to true))
        assertEquals(2, result.remainingCount)
        assertEquals(9, result.version)
        assertTrue(result.reservationsPaused)
        assertEquals(0, result.redeemedCount)
    }

    @Test fun `claim tombstone survives deletion of the personal reservation in the offer response`() {
        val offer = mapOf("offerId" to "offer", "companyId" to "partner", "version" to 1L)
        val detail = parseOfferReservationDetail(mapOf("offer" to offer, "reservation" to null, "alreadyUsed" to true))
        assertTrue(detail.alreadyUsed)
        assertNull(detail.reservation)
        assertFalse(parseOfferReservationDetail(mapOf("offer" to offer)).alreadyUsed)
    }

    @Test fun `application retains explicit requests without treating either as authorization`() {
        val input = PartnerApplications.toInput(PartnerApplicationForm("Partner", PartnerCategory.CAR_CARE, "Contact", "contact@example.com", selfServiceRequested = true, billboardRequested = true, billboardPlacementRequest = "  E6  ", address = " Street 1 "))!!
        assertTrue(input.selfServiceRequested)
        assertTrue(input.billboardRequested)
        assertEquals("E6", input.billboardPlacementRequest)
        assertEquals("Street 1", input.address)
    }
}
