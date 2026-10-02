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
import java.util.Locale
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
    var pagedCompanies by remember { mutableStateOf(emptyList<PartnerCompany>()) }
    var companiesCursor by remember { mutableStateOf<PartnerPageCursor?>(null) }
    var liveCompaniesCursor by remember { mutableStateOf<PartnerPageCursor?>(null) }
    var hasLoadedCompaniesPage by remember { mutableStateOf(false) }
    var companiesPageGeneration by remember { mutableStateOf(0) }
    var isLoadingMoreCompanies by remember { mutableStateOf(false) }
    var didFailLoadingMoreCompanies by remember { mutableStateOf(false) }

    val companiesState by
        remember(repository, reloadKey) { repository.observeActiveCompanies() }
            .collectAsState(initial = CompaniesState.Loading)
    LaunchedEffect(reloadKey) {
        pagedCompanies = emptyList()
        companiesCursor = null
        liveCompaniesCursor = null
        hasLoadedCompaniesPage = false
        companiesPageGeneration++
        isLoadingMoreCompanies = false
        didFailLoadingMoreCompanies = false
    }
    LaunchedEffect(companiesState) {
        val loaded = companiesState as? CompaniesState.Loaded ?: return@LaunchedEffect
        if (hasLoadedCompaniesPage && liveCompaniesCursor != loaded.nextCursor && pagedCompanies.isNotEmpty()) {
            pagedCompanies = emptyList()
            companiesPageGeneration++
            isLoadingMoreCompanies = false
        }
        hasLoadedCompaniesPage = true
        liveCompaniesCursor = loaded.nextCursor
        if (pagedCompanies.isEmpty()) companiesCursor = loaded.nextCursor
        didFailLoadingMoreCompanies = false
    }
    val displayedCompaniesState =
        (companiesState as? CompaniesState.Loaded)?.let { live ->
            CompaniesState.Loaded(
                companies =
                    (live.companies + pagedCompanies)
                        .distinctBy { it.id }
                        .sortedBy { it.name.lowercase(Locale.ROOT) },
                nextCursor = companiesCursor,
            )
        } ?: companiesState
    val offersState by
        remember(repository, reloadKey) { repository.observeActiveOffers() }
            .collectAsState(initial = OffersState.Loading)
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
    val savedIdsAreExhaustive =
        (savedIdsState as? SavedOfferIdsState.Loaded)?.isExhaustive ?: true
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

    val companyId = selectedCompanyId
    if (companyId == null) {
        PartnersRootScreen(
            state = displayedCompaniesState,
            offersState = offersState,
            savedOffersState = savedOffersState,
            savedOffersAreExhaustive = savedIdsAreExhaustive,
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
            onLoadMoreCompanies = {
                val cursor = companiesCursor
                val generation = companiesPageGeneration
                if (cursor != null && !isLoadingMoreCompanies) {
                    scope.launch {
                        isLoadingMoreCompanies = true
                        didFailLoadingMoreCompanies = false
                        try {
                            val page = repository.fetchActiveCompanies(cursor)
                            if (generation == companiesPageGeneration) {
                                pagedCompanies = (pagedCompanies + page.companies).distinctBy { it.id }
                                companiesCursor = page.nextCursor
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            if (generation == companiesPageGeneration) didFailLoadingMoreCompanies = true
                        } finally {
                            if (generation == companiesPageGeneration) isLoadingMoreCompanies = false
                        }
                    }
                }
            },
            canLoadMoreCompanies = companiesCursor != null,
            isLoadingMoreCompanies = isLoadingMoreCompanies,
            didFailLoadingMoreCompanies = didFailLoadingMoreCompanies,
            onRetrySaved = { savedReloadKey++ },
            onBack = onBack,
        )
        return
    }

    val cachedCompany =
        (displayedCompaniesState as? CompaniesState.Loaded)?.companies?.firstOrNull { it.id == companyId }
    val companyState by
        remember(repository, companyId, companyReloadKey) {
            repository.observeCompany(companyId)
        }
            .collectAsState(
                initial = cachedCompany?.let(CompanyState::Loaded) ?: CompanyState.Loading,
            )
    val company = (companyState as? CompanyState.Loaded)?.company
    val companyOffersState by
        remember(repository, companyId, reloadKey) { repository.observeActiveOffers(companyId) }
            .collectAsState(initial = OffersState.Loading)
    var pagedCompanyOffers by remember(companyId) { mutableStateOf(emptyList<PartnerOffer>()) }
    var companyOffersCursor by remember(companyId) { mutableStateOf<PartnerPageCursor?>(null) }
    var liveCompanyOffersCursor by remember(companyId) { mutableStateOf<PartnerPageCursor?>(null) }
    var hasLoadedCompanyOffersPage by remember(companyId) { mutableStateOf(false) }
    var companyOffersPageGeneration by remember(companyId) { mutableStateOf(0) }
    var isLoadingMoreCompanyOffers by remember(companyId) { mutableStateOf(false) }
    var didFailLoadingMoreCompanyOffers by remember(companyId) { mutableStateOf(false) }
    LaunchedEffect(companyOffersState) {
        val loaded = companyOffersState as? OffersState.Loaded ?: return@LaunchedEffect
        if (hasLoadedCompanyOffersPage && liveCompanyOffersCursor != loaded.nextCursor && pagedCompanyOffers.isNotEmpty()) {
            pagedCompanyOffers = emptyList()
            companyOffersPageGeneration++
            isLoadingMoreCompanyOffers = false
        }
        hasLoadedCompanyOffersPage = true
        liveCompanyOffersCursor = loaded.nextCursor
        if (pagedCompanyOffers.isEmpty()) companyOffersCursor = loaded.nextCursor
        didFailLoadingMoreCompanyOffers = false
    }
    val companyOffers =
        (
            (companyOffersState as? OffersState.Loaded)?.offers.orEmpty() +
                pagedCompanyOffers +
                savedOffers.filter { it.companyId == companyId }
        ).distinctBy { it.id }
    val displayedCompanyOffersState =
        (companyOffersState as? OffersState.Loaded)?.let {
            OffersState.Loaded(
                offers = companyOffers,
                isExhaustive = companyOffersCursor == null,
                nextCursor = companyOffersCursor,
            )
        } ?: companyOffersState
    val expandedDetail by
        remember(expandedOfferId, canAccessMemberOffers, repository) {
            val id = expandedOfferId
            if (id != null && canAccessMemberOffers) repository.observeOfferDetail(id) else flowOf(null)
        }
            .collectAsState(initial = null)
    val expandedVisibility by
        remember(expandedOfferId, canAccessMemberOffers, repository) {
            val id = expandedOfferId
            if (id != null && canAccessMemberOffers) repository.observeOffers(setOf(id)) else flowOf(OffersState.Loaded(emptyList()))
        }
            .collectAsState(initial = OffersState.Loading)
    LaunchedEffect(expandedOfferId, expandedVisibility) {
        if (expandedOfferId != null &&
            (expandedVisibility == OffersState.Error ||
                (expandedVisibility as? OffersState.Loaded)?.offers?.none { it.id == expandedOfferId } == true)
        ) {
            expandedOfferId = null
            offerCodeCoordinator?.reset()
        }
    }

    PartnerDetailScreen(
        companyState = companyState,
        offers = companyOffers,
        offersState = displayedCompanyOffersState,
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
        onRetryOffers = { reloadKey++ },
        onLoadMoreOffers = {
            val cursor = companyOffersCursor
            val requestedCompanyId = companyId
            val generation = companyOffersPageGeneration
            if (cursor != null && !isLoadingMoreCompanyOffers) {
                scope.launch {
                    isLoadingMoreCompanyOffers = true
                    didFailLoadingMoreCompanyOffers = false
                    try {
                        val page = repository.fetchActiveOffers(companyId, cursor)
                        if (selectedCompanyId == requestedCompanyId && generation == companyOffersPageGeneration) {
                            pagedCompanyOffers = (pagedCompanyOffers + page.offers).distinctBy { it.id }
                            companyOffersCursor = page.nextCursor
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (selectedCompanyId == requestedCompanyId && generation == companyOffersPageGeneration) {
                            didFailLoadingMoreCompanyOffers = true
                        }
                    } finally {
                        if (selectedCompanyId == requestedCompanyId && generation == companyOffersPageGeneration) {
                            isLoadingMoreCompanyOffers = false
                        }
                    }
                }
            }
        },
        canLoadMoreOffers = companyOffersCursor != null,
        isLoadingMoreOffers = isLoadingMoreCompanyOffers,
        didFailLoadingMoreOffers = didFailLoadingMoreCompanyOffers,
    )
}
