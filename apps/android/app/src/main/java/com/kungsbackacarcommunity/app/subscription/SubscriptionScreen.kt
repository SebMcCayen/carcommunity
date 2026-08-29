package com.kungsbackacarcommunity.app.subscription

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Subscription / membership purchase screen (Phase 12 slice 24). Shows the
 * current membership state, separate Plus/Supporter purchase actions, and
 * localized status / error text driven by [PurchaseFlowStatus].
 *
 * @param canSubscribe false disables the buttons (e.g. no Activity available or
 *   billing unavailable on this build).
 */
@Composable
fun SubscriptionScreen(
    isActiveMember: Boolean,
    status: PurchaseFlowStatus,
    canSubscribe: Boolean,
    onSubscribe: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val verifiedTier = (status as? PurchaseFlowStatus.Success)?.tier
    val hasVerifiedPaidTier = status is PurchaseFlowStatus.Success

    // This first Play slice supports one effective product at a time. Plan
    // changes stay disabled until Play SubscriptionUpdateParams and backend
    // multi-token effective-tier recomputation land together.
    val canStartNewPurchase =
        canSubscribe && !isActiveMember && !hasVerifiedPaidTier && !PurchaseFlow.isInFlight(status)

    val currentEntitlement =
        when {
            verifiedTier == "supporter" -> R.string.subscription_currentEntitlementSupporter
            verifiedTier == "plus" -> R.string.subscription_currentEntitlementPlus
            hasVerifiedPaidTier -> R.string.subscription_currentEntitlementMember
            isActiveMember -> R.string.subscription_currentEntitlementMember
            else -> R.string.subscription_currentEntitlementFree
        }

    AeroPage(title = stringResource(R.string.subscription_screenTitle), modifier = modifier) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.subscription_currentPlanTitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text =
                            stringResource(currentEntitlement),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.subscription_memberRequiredBody),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SubscriptionPlanCard(
                title = stringResource(R.string.subscription_plusTitle),
                body = stringResource(R.string.subscription_plusBody),
                action = stringResource(R.string.subscription_subscribePlusAction),
                enabled = canStartNewPurchase,
                onClick = { onSubscribe(PLUS_MONTHLY_PRODUCT_ID) },
            )

            SubscriptionPlanCard(
                title = stringResource(R.string.subscription_supporterTitle),
                body = stringResource(R.string.subscription_supporterBody),
                action = stringResource(R.string.subscription_subscribeSupporterAction),
                enabled = canStartNewPurchase,
                onClick = { onSubscribe(SUPPORTER_MONTHLY_PRODUCT_ID) },
            )

            statusTextRes(status)?.let { res ->
                val isError = status is PurchaseFlowStatus.Failed
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            // Explain why subscribing is disabled, unless the status text above
            // already carries the unavailable copy (Failed(Unavailable)), to
            // avoid rendering the same message twice.
            val statusAlreadyUnavailable =
                status == PurchaseFlowStatus.Failed(PurchaseFailureReason.Unavailable)
            if (!canSubscribe && !statusAlreadyUnavailable) {
                Text(
                    text = stringResource(R.string.subscription_statusUnavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@Composable
private fun SubscriptionPlanCard(
    title: String,
    body: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = action)
            }
        }
    }
}

/** Maps a [PurchaseFlowStatus] to its localized status/error string, or null for Idle. */
@StringRes
private fun statusTextRes(status: PurchaseFlowStatus): Int? =
    when (status) {
        PurchaseFlowStatus.Idle -> null
        PurchaseFlowStatus.Connecting -> R.string.subscription_statusConnecting
        PurchaseFlowStatus.Ready -> R.string.subscription_statusReady
        PurchaseFlowStatus.Purchasing -> R.string.subscription_statusPurchasing
        PurchaseFlowStatus.Verifying -> R.string.subscription_statusVerifying
        is PurchaseFlowStatus.Success -> R.string.subscription_statusSuccess
        PurchaseFlowStatus.Pending -> R.string.subscription_statusPending
        is PurchaseFlowStatus.Failed ->
            when (status.reason) {
                PurchaseFailureReason.Connection -> R.string.subscription_errorConnection
                PurchaseFailureReason.ProductUnavailable ->
                    R.string.subscription_errorProductUnavailable
                PurchaseFailureReason.Purchase -> R.string.subscription_errorPurchase
                PurchaseFailureReason.Verification -> R.string.subscription_errorVerification
                PurchaseFailureReason.InactivePurchase ->
                    R.string.subscription_errorInactivePurchase
                PurchaseFailureReason.Unavailable -> R.string.subscription_statusUnavailable
            }
    }

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun SubscriptionScreenPreview() {
    KccTheme {
        SubscriptionScreen(
            isActiveMember = false,
            status = PurchaseFlowStatus.Idle,
            canSubscribe = true,
            onSubscribe = {},
            onBack = {},
        )
    }
}
