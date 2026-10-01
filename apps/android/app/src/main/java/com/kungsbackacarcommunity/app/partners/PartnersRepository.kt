package com.kungsbackacarcommunity.app.partners

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the active-companies list. */
sealed interface CompaniesState {
    data object Loading : CompaniesState

    data object Error : CompaniesState

    data class Loaded(val companies: List<PartnerCompany>) : CompaniesState
}

sealed interface CompanyState {
    data object Loading : CompanyState
    data class Loaded(val company: PartnerCompany) : CompanyState
    data object Missing : CompanyState
    data object Error : CompanyState
}

sealed interface OffersState {
    data object Loading : OffersState
    data class Loaded(val offers: List<PartnerOffer>) : OffersState
    data object Error : OffersState
}

sealed interface SavedOfferIdsState {
    data object Loading : SavedOfferIdsState
    data class Loaded(val ids: Set<String>) : SavedOfferIdsState
    data object Error : SavedOfferIdsState
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

    /** One active company by id, used when a saved offer falls outside the capped directory. */
    fun observeCompany(companyId: String): Flow<CompanyState>

    fun observeActiveOffers(): Flow<OffersState>

    /** Active offer documents resolved directly for authoritative saved ids. */
    fun observeOffers(offerIds: Set<String>): Flow<OffersState>

    /** Member-gated offer detail; null when denied (non-member) or missing. */
    fun observeOfferDetail(offerId: String): Flow<OfferMemberDetail?>

    /** The set of offer ids the caller has bookmarked. */
    fun observeSavedOfferIds(uid: String): Flow<SavedOfferIdsState>

    /** partners.showOfferCode — reveals an active offer's code to a member. */
    suspend fun showOfferCode(offerId: String): String?

    /** Adds or removes the offer bookmark (users/{uid}/savedOffers/{offerId}). */
    suspend fun setSaved(uid: String, offerId: String, saved: Boolean)
}
