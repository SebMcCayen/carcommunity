package com.kungsbackacarcommunity.app.billboards

import org.junit.Assert.*
import org.junit.Test

class BillboardPartnerNavigationTest {
    private fun billboard(type: BillboardCtaType?, company: String? = "partner") =
        Billboard("billboard", "Headline", "Message", company, 57.0, 12.0, type)
    @Test fun `default and profile placements directly open the bound partner`() {
        assertEquals("partner", BillboardPartnerNavigation.directCompanyId(billboard(null)))
        assertEquals("partner", BillboardPartnerNavigation.directCompanyId(billboard(BillboardCtaType.PARTNER_PROFILE)))
    }
    @Test fun `legacy phone and website placements retain their existing popup actions`() {
        assertNull(BillboardPartnerNavigation.directCompanyId(billboard(BillboardCtaType.PHONE)))
        assertNull(BillboardPartnerNavigation.directCompanyId(billboard(BillboardCtaType.WEBSITE)))
        assertNull(BillboardPartnerNavigation.directCompanyId(billboard(null, null)))
    }
    @Test fun `disabled directory or unavailable repository retains the popup`() {
        for ((flag, repository) in listOf(false to true, true to false, false to false)) {
            val available = BillboardPartnerNavigation.available(flag, repository)
            assertFalse(available)
            assertNull(BillboardPartnerNavigation.directCompanyId(billboard(BillboardCtaType.PARTNER_PROFILE), available))
        }
        assertEquals("partner", BillboardPartnerNavigation.directCompanyId(billboard(null), BillboardPartnerNavigation.available(true, true)))
    }
}
