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
}
