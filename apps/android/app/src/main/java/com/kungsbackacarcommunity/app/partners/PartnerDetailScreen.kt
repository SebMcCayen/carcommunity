package com.kungsbackacarcommunity.app.partners

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.navigation.ExternalNavigation
import com.kungsbackacarcommunity.app.navigation.LatLng

/**
 * Partner company detail + its offers (Phase 12 slice 17). Stateless. Offers
 * show the teaser to any authenticated user; only a viewer who passes the
 * member-offer gate ([canAccessMemberOffers]) can save/unsave a bookmark, expand
 * the member detail, and reveal the discount code (callable). A viewer who does
 * not pass it sees the teaser plus an "upgrade" prompt in place of the member
 * content.
 * The gate is dark-flagged by the caller: while partnerMemberOffersRequirePaid
 * is ON it means a PAID subscriber (Plus/Supporter); while OFF it is the relaxed
 * member gate (every signed-in user), so the prompt never shows.
 * Only one offer is expanded at a time ([expandedOfferId]).
 */
@Composable
fun PartnerDetailScreen(
    companyState: CompanyState,
    offers: List<PartnerOffer>,
    offersState: OffersState = OffersState.Loaded(emptyList()),
    savedOfferIds: Set<String>,
    canAccessMemberOffers: Boolean,
    expandedOfferId: String?,
    expandedOfferDetail: OfferMemberDetail?,
    codeStatus: OfferCodeStatus,
    onToggleExpand: (String) -> Unit,
    onShowCode: (String) -> Unit,
    onToggleSave: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    onRetryCompany: () -> Unit = {},
    onRetryOffers: () -> Unit = {},
    onLoadMoreOffers: () -> Unit = {},
    canLoadMoreOffers: Boolean = false,
    isLoadingMoreOffers: Boolean = false,
    didFailLoadingMoreOffers: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val headerCompany = (companyState as? CompanyState.Loaded)?.company
    AeroPage(title = headerCompany?.name ?: stringResource(R.string.partners_detailTitle), modifier = modifier) {
            when (companyState) {
                CompanyState.Loading -> {
                    Text(
                        text = stringResource(R.string.partners_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@AeroPage
                }
                CompanyState.Error -> {
                    Text(
                        text = stringResource(R.string.partners_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onRetryCompany, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.partners_retry))
                    }
                    return@AeroPage
                }
                CompanyState.Missing -> {
                    Text(
                        text = stringResource(R.string.partners_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    return@AeroPage
                }
                is CompanyState.Loaded -> Unit
            }
            val company = (companyState as CompanyState.Loaded).company

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
            company.address?.takeIf { it.isNotBlank() }?.let { address ->
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PartnerDestinations.coordinates(company.latitude, company.longitude)?.let { (latitude, longitude) ->
                OutlinedButton(
                    onClick = {
                        ExternalNavigation.launch(
                            context = context,
                            destination = LatLng(longitude = longitude, latitude = latitude),
                            label = company.name,
                            onUnavailable = {
                                Toast.makeText(
                                    context,
                                    R.string.partners_cannotOpenNavigation,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.partners_navigateButton))
                }
            }
            PartnerDestinations.phone(company.phone)?.let { phone ->
                OutlinedButton(
                    onClick = {
                        launchPartnerIntent(
                            context,
                            Intent(Intent.ACTION_DIAL, Uri.parse(phone)),
                            R.string.partners_cannotOpenPhone,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.partners_callButton))
                }
            }
            PartnerDestinations.website(company.website)?.let { website ->
                OutlinedButton(
                    onClick = {
                        launchPartnerIntent(
                            context,
                            Intent(Intent.ACTION_VIEW, Uri.parse(website)),
                            R.string.partners_cannotOpenWebsite,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.partners_websiteButton))
                }
            }

            Text(
                text = stringResource(R.string.partnerOffers_sectionTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (offersState == OffersState.Error) {
                Text(
                    text = stringResource(R.string.partnerOffers_loadError),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetryOffers, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.partners_retry))
                }
            }
            if (offers.isNotEmpty()) {
                offers.forEach { offer ->
                    OfferCard(
                        offer = offer,
                        canAccessMemberOffers = canAccessMemberOffers,
                        isSaved = savedOfferIds.contains(offer.id),
                        isExpanded = expandedOfferId == offer.id,
                        detail = if (expandedOfferId == offer.id) expandedOfferDetail else null,
                        codeStatus = codeStatus,
                        onToggleExpand = { onToggleExpand(offer.id) },
                        onShowCode = { onShowCode(offer.id) },
                        onToggleSave = { onToggleSave(offer.id, !savedOfferIds.contains(offer.id)) },
                    )
                }
                if (canLoadMoreOffers) {
                    Button(
                        onClick = onLoadMoreOffers,
                        enabled = !isLoadingMoreOffers,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (isLoadingMoreOffers) R.string.partners_loading else R.string.partners_loadMore,
                            ),
                        )
                    }
                }
                if (didFailLoadingMoreOffers) {
                    Text(
                        text = stringResource(R.string.partnerOffers_loadError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                when (offersState) {
                    OffersState.Loading ->
                        Text(
                            text = stringResource(R.string.partners_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    OffersState.Error -> Unit
                    is OffersState.Loaded ->
                        if (offersState.isExhaustive) {
                            Text(
                                text = stringResource(R.string.partnerOffers_noOffers),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
            }
    }
}

private fun launchPartnerIntent(
    context: Context,
    intent: Intent,
    unavailableMessage: Int,
) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun OfferCard(
    offer: PartnerOffer,
    canAccessMemberOffers: Boolean,
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

            if (!canAccessMemberOffers) {
                // Member offers are paid-only. Free (Community) callers see the
                // public teaser above plus this upgrade prompt in place of the
                // member detail, bookmark, and discount code — mirroring the
                // server-side paid gate.
                Text(
                    text = stringResource(R.string.partnerOffers_upgradeForMemberOffers),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.partnerOffers_upgradeForMemberOffersHint),
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
                detail?.redemptionInstructions?.takeIf { it.isNotBlank() }?.let { instructions ->
                    Text(
                        text = instructions,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
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
                    val code = codeStatus.code?.takeIf { it.isNotBlank() }
                    Text(
                        text =
                            if (code != null) {
                                "${stringResource(R.string.partnerOffers_codeVisible)}: $code"
                            } else {
                                // Callable succeeded but returned no code — show a
                                // clear message rather than a blank/broken-looking code.
                                stringResource(R.string.partnerOffers_codeUnavailable)
                            },
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
