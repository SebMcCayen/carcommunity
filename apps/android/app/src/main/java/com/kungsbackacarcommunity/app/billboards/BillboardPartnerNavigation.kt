package com.kungsbackacarcommunity.app.billboards

/** Partner placements open the same company page as the directory; legacy actions stay intact. */
object BillboardPartnerNavigation {
    fun directCompanyId(billboard: Billboard?): String? = billboard?.companyId?.takeIf {
        it.isNotBlank() && billboard.callToActionType in setOf(null, BillboardCtaType.PARTNER_PROFILE)
    }
}
