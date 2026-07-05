package com.kungsbackacarcommunity.app.partners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/**
 * Partner company detail + its offers (Phase 12 slice 17). Stateless. Offers
 * show the teaser to any authenticated user; active members can save/unsave a
 * bookmark, expand the member detail, and reveal the discount code (callable).
 * Only one offer is expanded at a time ([expandedOfferId]).
 */
@Composable
fun PartnerDetailScreen(
    company: PartnerCompany?,
    offers: List<PartnerOffer>,
    savedOfferIds: Set<String>,
    isActiveMember: Boolean,
    expandedOfferId: String?,
    expandedOfferDetail: OfferMemberDetail?,
    codeStatus: OfferCodeStatus,
    onToggleExpand: (String) -> Unit,
    onShowCode: (String) -> Unit,
    onToggleSave: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (company == null) {
                Text(
                    text = stringResource(R.string.partners_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.profile_back))
                }
                return@Column
            }

            Text(
                text = company.name,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(company.category.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            company.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            company.website?.takeIf { it.isNotBlank() }?.let { website ->
                Text(
                    text = website,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            company.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                Text(
                    text = phone,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.partnerOffers_sectionTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (offers.isEmpty()) {
                Text(
                    text = stringResource(R.string.partnerOffers_noOffers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                offers.forEach { offer ->
                    OfferCard(
                        offer = offer,
                        isActiveMember = isActiveMember,
                        isSaved = savedOfferIds.contains(offer.id),
                        isExpanded = expandedOfferId == offer.id,
                        detail = if (expandedOfferId == offer.id) expandedOfferDetail else null,
                        codeStatus = codeStatus,
                        onToggleExpand = { onToggleExpand(offer.id) },
                        onShowCode = { onShowCode(offer.id) },
                        onToggleSave = { onToggleSave(offer.id, !savedOfferIds.contains(offer.id)) },
                    )
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.profile_back))
            }
        }
    }
}

@Composable
private fun OfferCard(
    offer: PartnerOffer,
    isActiveMember: Boolean,
    isSaved: Boolean,
    isExpanded: Boolean,
    detail: OfferMemberDetail?,
    codeStatus: OfferCodeStatus,
    onToggleExpand: () -> Unit,
    onShowCode: () -> Unit,
    onToggleSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = offer.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(offer.offerType.labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = offer.teaserText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!isActiveMember) {
                Text(
                    text = stringResource(R.string.partnerOffers_memberRequiredHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            OutlinedButton(onClick = onToggleSave, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text =
                        stringResource(
                            if (isSaved) R.string.partnerOffers_unsaveOffer else R.string.partnerOffers_saveOffer,
                        ),
                )
            }
            TextButton(onClick = onToggleExpand, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.partnerOffers_howToRedeem))
            }

            if (isExpanded) {
                detail?.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                detail?.terms?.takeIf { it.isNotBlank() }?.let { terms ->
                    Text(
                        text = "${stringResource(R.string.partnerOffers_terms)}: $terms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onShowCode, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.partnerOffers_showCode))
                }
                CodeArea(offerId = offer.id, codeStatus = codeStatus)
            }
        }
    }
}

@Composable
private fun CodeArea(offerId: String, codeStatus: OfferCodeStatus) {
    when (codeStatus) {
        is OfferCodeStatus.Loading ->
            if (codeStatus.offerId == offerId) {
                Text(
                    text = stringResource(R.string.partners_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        is OfferCodeStatus.Shown ->
            if (codeStatus.offerId == offerId) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                ) {
                    Text(
                        text = "${stringResource(R.string.partnerOffers_codeVisible)}: ${codeStatus.code ?: ""}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

        is OfferCodeStatus.Failed ->
            if (codeStatus.offerId == offerId) {
                Text(
                    text = stringResource(R.string.partnerOffers_codeLoadError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

        OfferCodeStatus.Idle -> Unit
    }
}
