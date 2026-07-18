package com.kungsbackacarcommunity.app.partners

import org.junit.Assert.assertEquals
import org.junit.Test

class PartnerTest {

    @Test
    fun `category parses wire values and defaults to OTHER`() {
        assertEquals(PartnerCategory.WORKSHOP, PartnerCategory.fromWire("workshop"))
        assertEquals(PartnerCategory.CAR_CARE, PartnerCategory.fromWire("car_care"))
        assertEquals(PartnerCategory.CHARGING, PartnerCategory.fromWire("charging"))
        assertEquals(PartnerCategory.OTHER, PartnerCategory.fromWire("unknown"))
        assertEquals(PartnerCategory.OTHER, PartnerCategory.fromWire(null))
    }

    @Test
    fun `offer type parses wire values and defaults to OTHER`() {
        assertEquals(PartnerOfferType.DISCOUNT_CODE, PartnerOfferType.fromWire("discount_code"))
        assertEquals(PartnerOfferType.MEMBER_BENEFIT, PartnerOfferType.fromWire("member_benefit"))
        assertEquals(PartnerOfferType.OTHER, PartnerOfferType.fromWire("mystery"))
    }

    @Test
    fun `offersForCompany filters by company and sorts by title`() {
        val offers =
            listOf(
                offer("o1", "c1", "Zebra"),
                offer("o2", "c2", "Alpha"),
                offer("o3", "c1", "apple"),
            )
        val result = Partners.offersForCompany(offers, "c1").map { it.id }
        assertEquals(listOf("o3", "o1"), result) // "apple" < "Zebra", case-insensitive
    }

    @Test
    fun `active companies and offers query limits are bounded`() {
        assertEquals(150L, Partners.ACTIVE_COMPANIES_QUERY_LIMIT)
        assertEquals(200L, Partners.ACTIVE_OFFERS_QUERY_LIMIT)
    }

    private fun offer(id: String, companyId: String, title: String) =
        PartnerOffer(id, companyId, title, "teaser", PartnerOfferType.DISCOUNT_CODE)
}
