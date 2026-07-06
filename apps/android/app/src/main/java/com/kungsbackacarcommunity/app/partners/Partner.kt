package com.kungsbackacarcommunity.app.partners

import java.util.Locale

/**
 * Partners domain model + enums (Phase 12 slice 17).
 *
 * Mirrors the backend partners-core contract: the company category and offer
 * type vocabularies and the three-tier offer split (teaser → member detail →
 * backend-only code). Pure Kotlin — JVM-testable. Category/type → label
 * mapping is localized in the screen.
 */

/** Company category (companies/{id}.category). */
enum class PartnerCategory(val wire: String) {
    WORKSHOP("workshop"),
    CAR_CARE("car_care"),
    PARTS("parts"),
    TIRES("tires"),
    CHARGING("charging"),
    RESTAURANT("restaurant"),
    RETAIL("retail"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String?): PartnerCategory = values().firstOrNull { it.wire == value } ?: OTHER
    }
}

/** Offer type (offers/{id}.offerType). */
enum class PartnerOfferType(val wire: String) {
    DISCOUNT_CODE("discount_code"),
    PERCENTAGE_DISCOUNT("percentage_discount"),
    FIXED_DISCOUNT("fixed_discount"),
    MEMBER_BENEFIT("member_benefit"),
    SPECIAL_OFFER("special_offer"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String?): PartnerOfferType = values().firstOrNull { it.wire == value } ?: OTHER
    }
}

/** Active partner company (companies/{id}) — public teaser fields. */
data class PartnerCompany(
    val id: String,
    val name: String,
    val category: PartnerCategory,
    val description: String?,
    val website: String?,
    val phone: String?,
    val latitude: Double?,
    val longitude: Double?,
)

/** Offer teaser (offers/{id}) — visible to any authenticated user. */
data class PartnerOffer(
    val id: String,
    val companyId: String,
    val title: String,
    val teaserText: String,
    val offerType: PartnerOfferType,
)

/** Member-gated offer detail (offers/{id}/details/member). */
data class OfferMemberDetail(
    val description: String?,
    val redemptionInstructions: String?,
    val terms: String?,
)

object Partners {
    /** Offers belonging to a company, in stable order (by title). */
    fun offersForCompany(offers: List<PartnerOffer>, companyId: String): List<PartnerOffer> =
        offers.filter { it.companyId == companyId }.sortedBy { it.title.lowercase(Locale.ROOT) }
}
