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

object Billboards {
    /**
     * Maximum active billboards the Firestore listener subscribes to
     * (createdAt ascending — the direction of the existing `billboards`
     * composite index, status ASC + createdAt ASC, so no new index deploy is
     * needed). Keeps the snapshot bounded as the collection grows without
     * bound. Trade-off: the oldest active billboards are kept and the newest
     * fall off past the cap, the reverse of typical "recent first" bounding.
     * Acceptable because billboard activation is an audited admin action
     * (a slow-moving, human-curated set), but if a newest-first cap is
     * wanted later, add a `status ASC, createdAt DESC` composite index and
     * flip the query direction.
     */
    const val ACTIVE_BILLBOARDS_QUERY_LIMIT = 150L
}
