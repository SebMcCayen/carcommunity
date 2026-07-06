package com.kungsbackacarcommunity.app.partners

import androidx.compose.runtime.Composable
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
    isActiveMember: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedCompanyId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedOfferId by rememberSaveable { mutableStateOf<String?>(null) }

    val companiesState by
        remember(repository) { repository.observeActiveCompanies() }
            .collectAsState(initial = CompaniesState.Loading)
    val offers by
        remember(repository) { repository.observeActiveOffers() }.collectAsState(initial = emptyList())
    val savedIds by
        remember(repository, uid, isActiveMember) {
            if (isActiveMember) repository.observeSavedOfferIds(uid) else flowOf(emptySet())
        }
            .collectAsState(initial = emptySet())
    val codeStatus by
        (offerCodeCoordinator?.status ?: flowOf(OfferCodeStatus.Idle))
            .collectAsState(initial = OfferCodeStatus.Idle)

    val companyId = selectedCompanyId
    if (companyId == null) {
        PartnersListScreen(
            state = companiesState,
            onOpenCompany = { selectedCompanyId = it },
            onBack = onBack,
        )
        return
    }

    val company = (companiesState as? CompaniesState.Loaded)?.companies?.firstOrNull { it.id == companyId }
    val companyOffers = Partners.offersForCompany(offers, companyId)
    val expandedDetail by
        remember(expandedOfferId, isActiveMember, repository) {
            val id = expandedOfferId
            if (id != null && isActiveMember) repository.observeOfferDetail(id) else flowOf(null)
        }
            .collectAsState(initial = null)

    PartnerDetailScreen(
        company = company,
        offers = companyOffers,
        savedOfferIds = savedIds,
        isActiveMember = isActiveMember,
        expandedOfferId = expandedOfferId,
        expandedOfferDetail = expandedDetail,
        codeStatus = codeStatus,
        onToggleExpand = { offerId ->
            expandedOfferId = if (expandedOfferId == offerId) null else offerId
            offerCodeCoordinator?.reset()
        },
        onShowCode = { offerId ->
            offerCodeCoordinator?.let { c -> scope.launch { c.reveal(offerId) } }
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
    )
}
