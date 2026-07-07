package com.kungsbackacarcommunity.app.subscription

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Subscription integration route (Phase 12 slice 24): wires the billing
 * repository + verifier into a [SubscriptionCoordinator] and drives the screen.
 *
 * Launching Play Billing needs an [Activity]; we resolve it from the local
 * context and disable the subscribe button when it is null (or when billing is
 * unavailable on this build). The billing connection is released on dispose.
 */
@Composable
fun SubscriptionRoute(
    billing: BillingRepository,
    verifier: SubscriptionVerifier?,
    isActiveMember: Boolean,
    onBack: () -> Unit,
) {
    val activity = LocalContext.current as? Activity
    val coordinator = remember(billing, verifier) { SubscriptionCoordinator(billing, verifier) }
    val status by coordinator.status.collectAsState()
    val scope = rememberCoroutineScope()

    DisposableEffect(billing) {
        onDispose { billing.close() }
    }

    SubscriptionScreen(
        isActiveMember = isActiveMember,
        status = status,
        canSubscribe = activity != null && verifier != null,
        onSubscribe = {
            activity?.let { a ->
                scope.launch { coordinator.subscribe { billing.launchPurchase(a) } }
            }
        },
        onBack = onBack,
    )
}
