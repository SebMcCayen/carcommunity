package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.BadgeLadderId
import com.kungsbackacarcommunity.app.badges.ladderNameRes
import com.kungsbackacarcommunity.app.badges.tierNameRes
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Kronjakt (crown hunt) screen (Phase 12 slice 16). Stateless: lists active
 * reward points and reports collect taps. Collecting requires passing the
 * member gate (the backend enforces the same gate, and while member gating is
 * disabled both admit any signed-in, non-suspended user); the GPS capture
 * background-location slice, so a collect with no position shows a "needs
 * location" hint rather than a failed claim.
 *
 * WORTH OPENING WITH ZERO CROWNS NEARBY. Spawned crowns are sparse and expire,
 * so an empty nearby list is the NORMAL case, not an error. The page therefore
 * leads with the member's own Kronjägare standing ([kronjagare]) and, when the
 * nearby list is empty, an explanatory empty state — never a blank screen. When
 * crowns ARE nearby the collectable list still renders below the stats.
 *
 * @param kronjagare the member's own crown-hunter tier standing, or null while
 *   the owner badge listener is still loading (the stats band is simply omitted;
 *   the rest of the page is already useful). Never carries a fabricated
 *   crowns-collected count — that counter is backend-only (see [KronjagareStanding]).
 */
@Composable
fun CrownHuntScreen(
    pointsState: CrownHuntPointsState,
    claimStatus: CrownHuntClaimStatus,
    passesMemberGate: Boolean,
    onCollect: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    kronjagare: KronjagareStanding? = null,
) {
    AeroPage(title = stringResource(R.string.crownHunt_screenTitle), modifier = modifier) {
        if (!passesMemberGate) {
            InfoCard(
                title = stringResource(R.string.subscription_teaserTitle),
                body = stringResource(R.string.subscription_memberRequiredBody),
            )
            return@AeroPage
        }

        ClaimStatusBanner(claimStatus)

        // The member's own crown-hunter standing sits ABOVE the nearby list, so
        // the page carries real, page-specific content whether or not a crown is
        // in range. Omitted (not blanked) until the badge listener resolves.
        kronjagare?.let { KronjagareStatsCard(it) }

        when (pointsState) {
            CrownHuntPointsState.Loading ->
                Text(
                    text = stringResource(R.string.crownHunt_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            CrownHuntPointsState.Error ->
                Text(
                    text = stringResource(R.string.crownHunt_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

            is CrownHuntPointsState.Loaded ->
                if (pointsState.points.isEmpty()) {
                    // The core fix: no crown nearby is the ordinary case, so say
                    // what Kronjakt is and that none is in range right now —
                    // friendly, not an error — instead of drawing nothing.
                    CrownHuntEmptyState()
                } else {
                    pointsState.points.forEach { point ->
                        PointCard(
                            point = point,
                            collectEnabled = claimStatus != CrownHuntClaimStatus.Claiming,
                            onCollect = { onCollect(point.id) },
                        )
                    }
                }
        }
    }
}

/** Friendly "what Kronjakt is + none nearby right now" card for the empty list. */
@Composable
private fun CrownHuntEmptyState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.crownHunt_emptyHeadline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.crownHunt_emptyBody),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The member's own Kronjägare standing: the tier held (or an invitation when
 * none is), the next tier and its crown threshold. Reuses the badge catalog's
 * ladder/tier strings so it can never disagree with the profile badge wall.
 *
 * Shows NO crowns-collected count and NO progress bar: that counter is
 * backend-only, so the note explains the rank is tallied server-side rather than
 * inventing a number (mirrors the profile's own honesty about this ladder).
 */
@Composable
private fun KronjagareStatsCard(standing: KronjagareStanding) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.crownHunt_statsTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val ladderName = stringResource(ladderNameRes(BadgeLadderId.KRONJAGARE))
            Text(
                text =
                    standing.highestTier?.let { tier ->
                        stringResource(
                            R.string.crownHunt_statsRankCurrent,
                            ladderName + " " + stringResource(tierNameRes(tier)),
                        )
                    } ?: stringResource(R.string.crownHunt_statsRankNone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (standing.nextTier != null && standing.nextThresholdCrowns != null) {
                Text(
                    text =
                        stringResource(
                            R.string.crownHunt_statsNext,
                            ladderName + " " + stringResource(tierNameRes(standing.nextTier)),
                            standing.nextThresholdCrowns.toString(),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = stringResource(R.string.crownHunt_statsComplete),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                // Why there is no "23 / 50" count here: the rank is tallied on the
                // server (badgeProgress is client-denied), so we name the goal but
                // never fake the progress toward it.
                text = stringResource(R.string.crownHunt_statsServerNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClaimStatusBanner(status: CrownHuntClaimStatus) {
    val message =
        when (status) {
            CrownHuntClaimStatus.Idle -> null
            CrownHuntClaimStatus.Claiming -> stringResource(R.string.crownHunt_claiming)
            CrownHuntClaimStatus.NeedsLocation ->
                stringResource(R.string.crownHunt_locationUnavailable)
            CrownHuntClaimStatus.Failed -> stringResource(R.string.crownHunt_errorClaim)
            is CrownHuntClaimStatus.Done -> claimResultMessage(status.outcome.result)
        }
    if (message != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun claimResultMessage(result: CrownHuntClaimResult): String =
    stringResource(
        when (result) {
            CrownHuntClaimResult.AWARDED -> R.string.crownHunt_resultAwarded
            CrownHuntClaimResult.ALREADY_CLAIMED -> R.string.crownHunt_resultAlreadyClaimed
            CrownHuntClaimResult.OUTSIDE_GEOFENCE -> R.string.crownHunt_resultOutsideGeofence
            CrownHuntClaimResult.MOVING_TOO_FAST -> R.string.crownHunt_resultMovingTooFast
            CrownHuntClaimResult.POSITION_TOO_OLD -> R.string.crownHunt_resultPositionTooOld
            CrownHuntClaimResult.POINT_INACTIVE -> R.string.crownHunt_resultPointInactive
            CrownHuntClaimResult.COOLDOWN_ACTIVE -> R.string.crownHunt_resultCooldownActive
            CrownHuntClaimResult.DAILY_LIMIT_REACHED -> R.string.crownHunt_resultDailyLimit
            CrownHuntClaimResult.RISK_REVIEW -> R.string.crownHunt_resultRiskReview
            CrownHuntClaimResult.FEATURE_DISABLED -> R.string.crownHunt_resultFeatureDisabled
            CrownHuntClaimResult.NOT_ELIGIBLE -> R.string.crownHunt_resultNotEligible
        },
    )

@Composable
private fun PointCard(point: CrownHuntPoint, collectEnabled: Boolean, onCollect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = point.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            point.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                // crownHunt_rewardLabel carries a {points} placeholder
                // ("Reward: {points} KP") — substitute it, don't append.
                text =
                    stringResource(R.string.crownHunt_rewardLabel)
                        .replace("{points}", point.rewardPoints.toString()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(
                onClick = onCollect,
                enabled = collectEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.crownHunt_collectButton))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
