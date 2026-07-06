package com.kungsbackacarcommunity.app.partners

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the active-companies list. */
sealed interface CompaniesState {
    data object Loading : CompaniesState

    data object Error : CompaniesState

    data class Loaded(val companies: List<PartnerCompany>) : CompaniesState
}

/**
 * Partner read + offer operations (Phase 12 slice 17). Firebase-free interface
 * so the route/screens are unit- and UI-testable with fakes.
 *
 * Companies and offer teasers are rules-gated reads (authenticated + active).
 * The offer detail is member-gated. The discount code is served ONLY by the
 * partners.showOfferCode callable. Saving an offer is a direct member write of
 * `{ offerId, savedAt }` under users/{uid}/savedOffers.
 */
interface PartnersRepository {
    fun observeActiveCompanies(): Flow<CompaniesState>

    fun observeActiveOffers(): Flow<List<PartnerOffer>>

    /** Member-gated offer detail; null when denied (non-member) or missing. */
    fun observeOfferDetail(offerId: String): Flow<OfferMemberDetail?>

    /** The set of offer ids the caller has bookmarked. */
    fun observeSavedOfferIds(uid: String): Flow<Set<String>>

    /** partners.showOfferCode — reveals an active offer's code to a member. */
    suspend fun showOfferCode(offerId: String): String?

    /** Adds or removes the offer bookmark (users/{uid}/savedOffers/{offerId}). */
    suspend fun setSaved(uid: String, offerId: String, saved: Boolean)
}
