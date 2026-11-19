package com.kungsbackacarcommunity.app.billboards

/** Partner placements open the same company page as the directory; legacy actions stay intact. */
object BillboardPartnerNavigation {
    fun available(partnersEnabled: Boolean, repositoryAvailable: Boolean): Boolean = partnersEnabled && repositoryAvailable

    fun directCompanyId(billboard: Billboard?, navigationAvailable: Boolean = true): String? = billboard?.companyId?.takeIf {
        navigationAvailable && it.isNotBlank() && billboard.callToActionType in setOf(null, BillboardCtaType.PARTNER_PROFILE)
    }
}
