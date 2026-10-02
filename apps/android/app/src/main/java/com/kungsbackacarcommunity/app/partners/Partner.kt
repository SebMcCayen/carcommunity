package com.kungsbackacarcommunity.app.partners

import java.net.URI
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
    val address: String? = null,
)

/** Offer teaser (offers/{id}) — visible to any authenticated user. */
data class PartnerOffer(
    val id: String,
    val companyId: String,
    val title: String,
    val teaserText: String,
    val offerType: PartnerOfferType,
    val partnerCompanyName: String? = null,
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

    /** Active offers bookmarked by the member, in stable title order. */
    fun savedOffers(offers: List<PartnerOffer>, savedIds: Set<String>): List<PartnerOffer> =
        offers.filter { it.id in savedIds }.sortedBy { it.title.lowercase(Locale.ROOT) }

    /**
     * Maximum active companies the Firestore listener subscribes to (newest
     * first by createdAt, though the list itself displays alphabetically by
     * name). Keeps the snapshot bounded as `companies` grows without bound.
     * Requires the `companies` composite index (status ASC, createdAt DESC)
     * added alongside this constant — see firebase/firestore.indexes.json and
     * the PR description for the required index deploy.
     */
    const val ACTIVE_COMPANIES_QUERY_LIMIT = 150L

    /**
     * Maximum active offers the Firestore listener subscribes to (newest
     * first by createdAt). Keeps the snapshot bounded as `offers` grows
     * without bound. Requires the `offers` composite index (status ASC,
     * createdAt DESC) added alongside this constant — the collection already
     * has a `companyId, status, createdAt` composite index, but that one
     * doesn't apply here since this query has no `companyId` equality filter.
     * See firebase/firestore.indexes.json and the PR description for the
     * required index deploy.
     */
    const val ACTIVE_OFFERS_QUERY_LIMIT = 200L

    /** Page size for active offers shown in one company detail. */
    const val COMPANY_OFFERS_QUERY_LIMIT = 50L

    /** Maximum recent bookmarks resolved by the live saved-offers surface. */
    const val SAVED_OFFERS_QUERY_LIMIT = 30L
}

/** Mirrors the backend authority for member-only partner offer data. */
object PartnerOfferAccess {
    fun allows(
        isAdmin: Boolean,
        isPaidSubscriber: Boolean,
        isAccountRestricted: Boolean,
    ): Boolean = !isAccountRestricted && (isAdmin || isPaidSubscriber)
}

/** Validated external destinations for partner actions. */
object PartnerDestinations {
    fun website(rawValue: String?): String? {
        val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http")) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return runCatching {
            URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, null).toString()
        }.getOrNull()
    }

    fun phone(rawValue: String?): String? {
        val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!value.all { it in '0'..'9' || it in "+ ()-." }) return null
        if ('+' in value.drop(1)) return null
        val digits = value.filter { it in '0'..'9' }
        if (digits.length < 3) return null
        return "tel:${if (value.startsWith('+')) "+" else ""}$digits"
    }

    fun coordinates(latitude: Double?, longitude: Double?): Pair<Double, Double>? {
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }
}
