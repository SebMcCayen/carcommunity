package com.kungsbackacarcommunity.app.crownhunt

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/**
 * Kronjakt (crown hunt) screen (Phase 12 slice 16). Stateless: lists active
 * reward points and reports collect taps. Collecting requires an active member
 * (the backend also enforces this); the actual GPS capture lands with the map/
 * background-location slice, so a collect with no position shows a "needs
 * location" hint rather than a failed claim.
 */
@Composable
fun CrownHuntScreen(
    pointsState: CrownHuntPointsState,
    claimStatus: CrownHuntClaimStatus,
    isActiveMember: Boolean,
    onCollect: (String) -> Unit,
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
            Text(
                text = stringResource(R.string.crownHunt_screenTitle),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            WarningCard(stringResource(R.string.crownHunt_safetyNoDriving))

            if (!isActiveMember) {
                InfoCard(
                    title = stringResource(R.string.subscription_teaserTitle),
                    body = stringResource(R.string.subscription_memberRequiredBody),
                )
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.profile_back))
                }
                return@Column
            }

            ClaimStatusBanner(claimStatus)

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
                    pointsState.points.forEach { point ->
                        PointCard(
                            point = point,
                            collectEnabled = claimStatus != CrownHuntClaimStatus.Claiming,
                            onCollect = { onCollect(point.id) },
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
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
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
