package com.kungsbackacarcommunity.app.partners

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Partners integration route (Phase 12 slice 17): owns the list ↔ detail
 * selection and the expanded-offer state, and wires the repository flows +
 * offer-code coordinator into the stateless screens.
 */
@Composable
fun PartnersRoute(
    repository: PartnersRepository,
    offerCodeCoordinator: OfferCodeCoordinator?,
    uid: String,
    // Verified paid subscriber or admin; legacy flags never unlock member offers.
    canAccessMemberOffers: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedCompanyId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedOfferId by rememberSaveable { mutableStateOf<String?>(null) }
    var rootSection by rememberSaveable { mutableStateOf(PartnersRootSection.DIRECTORY) }
    // Bumped by the "try again" affordance to re-subscribe the companies flow.
    var reloadKey by rememberSaveable { mutableStateOf(0) }
    var savedReloadKey by rememberSaveable { mutableStateOf(0) }
    var companyReloadKey by rememberSaveable { mutableStateOf(0) }

    val companiesState by
        remember(repository, reloadKey) { repository.observeActiveCompanies() }
            .collectAsState(initial = CompaniesState.Loading)
    val offersState by
        remember(repository, reloadKey) { repository.observeActiveOffers() }
            .collectAsState(initial = OffersState.Loading)
    val offers = (offersState as? OffersState.Loaded)?.offers.orEmpty()
    val savedIdsState by
        remember(repository, uid, canAccessMemberOffers, savedReloadKey) {
            if (canAccessMemberOffers) {
                repository.observeSavedOfferIds(uid)
            } else {
                flowOf(SavedOfferIdsState.Loaded(emptySet()))
            }
        }
            .collectAsState(initial = SavedOfferIdsState.Loading)
    val savedIds = (savedIdsState as? SavedOfferIdsState.Loaded)?.ids.orEmpty()
    val savedOffersState by
        remember(repository, savedIdsState, canAccessMemberOffers, savedReloadKey) {
            if (!canAccessMemberOffers) {
                flowOf(OffersState.Loaded(emptyList()))
            } else {
                when (val state = savedIdsState) {
                    SavedOfferIdsState.Loading -> flowOf(OffersState.Loading)
                    SavedOfferIdsState.Error -> flowOf(OffersState.Error)
                    is SavedOfferIdsState.Loaded -> repository.observeOffers(state.ids)
                }
            }
        }
            .collectAsState(initial = OffersState.Loading)
    val savedOffers = (savedOffersState as? OffersState.Loaded)?.offers.orEmpty()
    val accessibleOffers = (offers + savedOffers).distinctBy { it.id }
    val codeStatus by
        (offerCodeCoordinator?.status ?: flowOf(OfferCodeStatus.Idle))
            .collectAsState(initial = OfferCodeStatus.Idle)

    DisposableEffect(offerCodeCoordinator) {
        onDispose { offerCodeCoordinator?.reset() }
    }

    // System/gesture Back returns from the company detail to the list; at the
    // list root it is disabled so the shell's BackHandler returns to Home.
    BackHandler(enabled = selectedCompanyId != null) {
        selectedCompanyId = null
        expandedOfferId = null
        offerCodeCoordinator?.reset()
    }

    LaunchedEffect(canAccessMemberOffers, uid) {
        if (!canAccessMemberOffers) {
            expandedOfferId = null
            offerCodeCoordinator?.reset()
        }
    }

    LaunchedEffect(accessibleOffers, expandedOfferId) {
        if (expandedOfferId != null && accessibleOffers.none { it.id == expandedOfferId }) {
            expandedOfferId = null
            offerCodeCoordinator?.reset()
        }
    }

    val companyId = selectedCompanyId
    if (companyId == null) {
        PartnersRootScreen(
            state = companiesState,
            offersState = offersState,
            savedOffersState = savedOffersState,
            canAccessMemberOffers = canAccessMemberOffers,
            section = rootSection,
            onSectionChange = { rootSection = it },
            onOpenCompany = { selectedCompanyId = it },
            onOpenSavedOffer = { savedCompanyId, offerId ->
                selectedCompanyId = savedCompanyId
                expandedOfferId = offerId
                offerCodeCoordinator?.reset()
            },
            onRetry = { reloadKey++ },
            onRetrySaved = { savedReloadKey++ },
            onBack = onBack,
        )
        return
    }

    val cachedCompany =
        (companiesState as? CompaniesState.Loaded)?.companies?.firstOrNull { it.id == companyId }
    val companyState by
        remember(repository, companyId, cachedCompany, companyReloadKey) {
            cachedCompany?.let { flowOf(CompanyState.Loaded(it)) }
                ?: repository.observeCompany(companyId)
        }
            .collectAsState(
                initial = cachedCompany?.let(CompanyState::Loaded) ?: CompanyState.Loading,
            )
    val company = (companyState as? CompanyState.Loaded)?.company
    val companyOffers = Partners.offersForCompany(accessibleOffers, companyId)
    val expandedDetail by
        remember(expandedOfferId, canAccessMemberOffers, repository) {
            val id = expandedOfferId
            if (id != null && canAccessMemberOffers) repository.observeOfferDetail(id) else flowOf(null)
        }
            .collectAsState(initial = null)

    PartnerDetailScreen(
        companyState = companyState,
        offers = companyOffers,
        offersAreExhaustive = (offersState as? OffersState.Loaded)?.isExhaustive == true,
        savedOfferIds = savedIds,
        canAccessMemberOffers = canAccessMemberOffers,
        expandedOfferId = expandedOfferId,
        expandedOfferDetail = if (canAccessMemberOffers) expandedDetail else null,
        codeStatus = if (canAccessMemberOffers) codeStatus else OfferCodeStatus.Idle,
        onToggleExpand = { offerId ->
            expandedOfferId = if (expandedOfferId == offerId) null else offerId
            offerCodeCoordinator?.reset()
        },
        onShowCode = { offerId ->
            if (canAccessMemberOffers) offerCodeCoordinator?.let { c -> scope.launch { c.reveal(offerId) } }
        },
        onToggleSave = { offerId, saved ->
            scope.launch {
                try {
                    repository.setSaved(uid, offerId, saved)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Bookmark toggles are best-effort; a failed write is a no-op
                    // for the UI (the live savedOfferIds flow stays authoritative).
                }
            }
        },
        onBack = {
            selectedCompanyId = null
            expandedOfferId = null
            offerCodeCoordinator?.reset()
        },
        onRetryCompany = { companyReloadKey++ },
    )
}
