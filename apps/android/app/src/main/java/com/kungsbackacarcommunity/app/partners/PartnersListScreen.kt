package com.kungsbackacarcommunity.app.partners

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage

enum class PartnersRootSection {
    DIRECTORY,
    SAVED,
}

/** Shared Partners root, including the saved-offers surface available on iOS. */
@Composable
fun PartnersRootScreen(
    state: CompaniesState,
    offersState: OffersState,
    savedOffersState: OffersState,
    savedOffersAreExhaustive: Boolean = true,
    canAccessMemberOffers: Boolean,
    section: PartnersRootSection,
    onSectionChange: (PartnersRootSection) -> Unit,
    onOpenCompany: (String) -> Unit,
    onOpenSavedOffer: (companyId: String, offerId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onRetrySaved: (() -> Unit)? = null,
    onLoadMoreCompanies: (() -> Unit)? = null,
    canLoadMoreCompanies: Boolean = false,
    isLoadingMoreCompanies: Boolean = false,
    didFailLoadingMoreCompanies: Boolean = false,
) {
    AeroPage(title = stringResource(R.string.partners_screenTitle), modifier = modifier) {
        TabRow(selectedTabIndex = section.ordinal) {
            Tab(
                selected = section == PartnersRootSection.DIRECTORY,
                onClick = { onSectionChange(PartnersRootSection.DIRECTORY) },
                text = { Text(stringResource(R.string.partners_directory)) },
            )
            Tab(
                selected = section == PartnersRootSection.SAVED,
                onClick = { onSectionChange(PartnersRootSection.SAVED) },
                text = { Text(stringResource(R.string.partners_saved)) },
            )
        }

        when (section) {
            PartnersRootSection.DIRECTORY ->
                PartnersDirectoryContent(
                    state, offersState, onOpenCompany, onRetry,
                    onLoadMoreCompanies, canLoadMoreCompanies,
                    isLoadingMoreCompanies, didFailLoadingMoreCompanies,
                )
            PartnersRootSection.SAVED ->
                SavedOffersContent(
                    savedOffersState = savedOffersState,
                    savedOffersAreExhaustive = savedOffersAreExhaustive,
                    companies = (state as? CompaniesState.Loaded)?.companies.orEmpty(),
                    canAccessMemberOffers = canAccessMemberOffers,
                    onOpenSavedOffer = onOpenSavedOffer,
                    onRetry = onRetrySaved,
                )
        }
    }
}

/**
 * Partner companies list (Phase 12 slice 17). Stateless: renders [state] and
 * reports taps. Any authenticated user sees active companies.
 */
@Composable
fun PartnersListScreen(
    state: CompaniesState,
    offersState: OffersState = OffersState.Loaded(emptyList()),
    onOpenCompany: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Re-invokes the companies load; when null the error state shows no retry.
    onRetry: (() -> Unit)? = null,
) {
    AeroPage(title = stringResource(R.string.partners_screenTitle), modifier = modifier) {
        PartnersDirectoryContent(state, offersState, onOpenCompany, onRetry)
    }
}

@Composable
private fun PartnersDirectoryContent(
    state: CompaniesState,
    offersState: OffersState,
    onOpenCompany: (String) -> Unit,
    onRetry: (() -> Unit)?,
    onLoadMore: (() -> Unit)? = null,
    canLoadMore: Boolean = false,
    isLoadingMore: Boolean = false,
    didFailLoadingMore: Boolean = false,
) {
    when (state) {
        CompaniesState.Loading ->
            Text(
                text = stringResource(R.string.partners_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        CompaniesState.Error -> {
            Text(
                text = stringResource(R.string.partners_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.partners_retry))
                }
            }
        }

        is CompaniesState.Loaded ->
            if (state.companies.isEmpty()) {
                Text(
                    text = stringResource(R.string.partners_noPartnersNearby),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (offersState == OffersState.Error) {
                    Text(
                        text = stringResource(R.string.partnerOffers_loadError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (onRetry != null) {
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(R.string.partners_retry))
                        }
                    }
                }
                state.companies.forEach { company ->
                    CompanyCard(
                        company = company,
                        offerCount =
                            (offersState as? OffersState.Loaded)
                                ?.takeIf { it.isExhaustive }
                                ?.offers
                                ?.let { offers -> Partners.offersForCompany(offers, company.id).size },
                        onClick = { onOpenCompany(company.id) },
                    )
                }
                if (canLoadMore && onLoadMore != null) {
                    Button(
                        onClick = onLoadMore,
                        enabled = !isLoadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (isLoadingMore) R.string.partners_loading else R.string.partners_loadMore,
                            ),
                        )
                    }
                }
                if (didFailLoadingMore && onLoadMore != null) {
                    Text(
                        text = stringResource(R.string.partners_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
    }
}

@Composable
private fun SavedOffersContent(
    savedOffersState: OffersState,
    savedOffersAreExhaustive: Boolean,
    companies: List<PartnerCompany>,
    canAccessMemberOffers: Boolean,
    onOpenSavedOffer: (companyId: String, offerId: String) -> Unit,
    onRetry: (() -> Unit)?,
) {
    if (!canAccessMemberOffers) {
        Text(
            text = stringResource(R.string.partnerOffers_upgradeForMemberOffers),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.partnerOffers_upgradeForMemberOffersHint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    when (savedOffersState) {
        OffersState.Loading -> {
            Text(
                text = stringResource(R.string.partners_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        OffersState.Error -> {
            Text(
                text = stringResource(R.string.partnerOffers_loadError),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.partners_retry))
                }
            }
            return
        }
        is OffersState.Loaded -> Unit
    }
    val savedOffers = savedOffersState.offers.sortedBy { it.title.lowercase() }
    if (!savedOffersAreExhaustive) {
        Text(
            text = stringResource(R.string.partnerOffers_savedLimitedBody),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (savedOffers.isEmpty()) {
        Text(
            text = stringResource(R.string.partnerOffers_savedEmptyTitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.partnerOffers_savedEmptyBody),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    savedOffers.forEach { offer ->
        val companyName =
            companies.firstOrNull { it.id == offer.companyId }?.name
                ?: offer.partnerCompanyName?.takeIf { it.isNotBlank() }
        Card(
            modifier =
                Modifier.fillMaxWidth().clickable {
                    onOpenSavedOffer(offer.companyId, offer.id)
                },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = offer.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                companyName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(offer.offerType.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CompanyCard(
    company: PartnerCompany,
    offerCount: Int?,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = company.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(company.category.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            offerCount?.let { count ->
                Text(
                    text =
                        stringResource(
                            if (count == 1) {
                                R.string.partnerOffers_offerCountOne
                            } else {
                                R.string.partnerOffers_offerCountOther
                            },
                            count,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
