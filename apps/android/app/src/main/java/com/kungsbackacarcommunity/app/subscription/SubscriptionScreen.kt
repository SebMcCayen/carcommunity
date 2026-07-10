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
 * current membership state, a subscribe button that launches Play Billing, and
 * localized status / error text driven by [PurchaseFlowStatus].
 *
 * @param canSubscribe false disables the button (e.g. no Activity available or
 *   billing unavailable on this build).
 */
@Composable
fun SubscriptionScreen(
    isActiveMember: Boolean,
    status: PurchaseFlowStatus,
    canSubscribe: Boolean,
    onSubscribe: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AeroPage(title = stringResource(R.string.subscription_screenTitle), modifier = modifier) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.subscription_memberMonthlyTitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text =
                            stringResource(
                                if (isActiveMember) {
                                    R.string.subscription_currentEntitlementMember
                                } else {
                                    R.string.subscription_currentEntitlementFree
                                },
                            ),
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

            Button(
                onClick = onSubscribe,
                enabled = canSubscribe && !PurchaseFlow.isInFlight(status),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.subscription_subscribeAction))
            }

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

/** Maps a [PurchaseFlowStatus] to its localized status/error string, or null for Idle. */
@StringRes
private fun statusTextRes(status: PurchaseFlowStatus): Int? =
    when (status) {
        PurchaseFlowStatus.Idle -> null
        PurchaseFlowStatus.Connecting -> R.string.subscription_statusConnecting
        PurchaseFlowStatus.Ready -> R.string.subscription_statusReady
        PurchaseFlowStatus.Purchasing -> R.string.subscription_statusPurchasing
        PurchaseFlowStatus.Verifying -> R.string.subscription_statusVerifying
        PurchaseFlowStatus.Success -> R.string.subscription_statusSuccess
        is PurchaseFlowStatus.Failed ->
            when (status.reason) {
                PurchaseFailureReason.Connection -> R.string.subscription_errorConnection
                PurchaseFailureReason.ProductUnavailable ->
                    R.string.subscription_errorProductUnavailable
                PurchaseFailureReason.Purchase -> R.string.subscription_errorPurchase
                PurchaseFailureReason.Verification -> R.string.subscription_errorVerification
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
