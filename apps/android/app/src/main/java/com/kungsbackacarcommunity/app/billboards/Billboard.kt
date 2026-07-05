package com.kungsbackacarcommunity.app.billboards

/**
 * Digital billboards domain (Phase 12 slice 20). Read active billboards +
 * record interactions. Pure Kotlin.
 */
enum class BillboardInteractionType(val wire: String) {
    IMPRESSION("impression"),
    OPEN("open"),
    NAVIGATE("navigate"),
    PHONE("phone"),
    WEBSITE("website"),
    OFFER_VIEW("offer_view"),
}

/** An active sponsored billboard (billboards/{id}). */
data class Billboard(
    val id: String,
    val headline: String,
    val message: String?,
    val companyId: String?,
)
