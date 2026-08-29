package com.kungsbackacarcommunity.app.subscription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Orchestrates the subscription purchase → verify flow (Phase 12 slice 24):
 * connect → query products → launch Play purchase → await the purchaseToken →
 * call `subscription-verify`. The backend acknowledges only after verification
 * and entitlement application. Pure Kotlin (the Play-UI launch is
 * injected as a `() -> Unit` so no Android types leak in) and therefore
 * unit-testable with fakes; it exposes a [StateFlow] of [PurchaseFlowStatus] the
 * UI observes.
 *
 * Because `subscription-verify` fails closed until store credentials exist, a
 * real purchase currently ends at [PurchaseFlowStatus.Failed] with
 * [PurchaseFailureReason.Verification] — documented, expected, no entitlement.
 */
class SubscriptionCoordinator(
    private val billing: BillingRepository,
    private val verifier: SubscriptionVerifier?,
) {
    private val state = MutableStateFlow<PurchaseFlowStatus>(PurchaseFlowStatus.Idle)
    val status: StateFlow<PurchaseFlowStatus> = state.asStateFlow()

    /**
     * Runs the full flow for one immutable Play [productId]. [launchPurchase]
     * triggers the Play purchase UI. Suspends until the
     * purchase is surfaced and verified (or the flow fails). Guards against
     * re-entry while a flow is in flight.
     */
    suspend fun subscribe(
        productId: String,
        launchPurchase: () -> PurchaseLaunchResult,
    ) {
        if (!PurchaseFlow.canStart(state.value)) return
        if (productId !in SUBSCRIPTION_PRODUCT_IDS) {
            state.value = PurchaseFlow.failed(PurchaseFailureReason.ProductUnavailable)
            return
        }
        if (verifier == null) {
            state.value = PurchaseFlow.failed(PurchaseFailureReason.Unavailable)
            return
        }
        try {
            state.value = PurchaseFlowStatus.Connecting
            if (billing.connect() != BillingConnectionResult.Connected) {
                state.value = PurchaseFlow.failed(PurchaseFailureReason.Connection)
                return
            }
            if (productId !in billing.queryProducts().availableProductIds) {
                state.value = PurchaseFlow.failed(PurchaseFailureReason.ProductUnavailable)
                return
            }
            state.value = PurchaseFlowStatus.Ready

            state.value = PurchaseFlowStatus.Purchasing
            // Subscribe BEFORE launch. Billing can report a result immediately;
            // subscribing afterwards creates a race that loses the event and
            // leaves the UI stuck forever. Also honor launchBillingFlow's
            // synchronous failure result instead of waiting for a callback that
            // Play will never send.
            val outcome =
                coroutineScope {
                    val awaiting =
                        async(start = CoroutineStart.UNDISPATCHED) { billing.purchases.first() }
                    if (launchPurchase() != PurchaseLaunchResult.Launched) {
                        awaiting.cancel()
                        null
                    } else {
                        awaiting.await()
                    }
                }
            if (outcome == null) {
                state.value = PurchaseFlow.failed(PurchaseFailureReason.Purchase)
                return
            }
            when (outcome) {
                is PurchaseResult.Purchased -> {
                    state.value = PurchaseFlowStatus.Verifying
                    val verified = verifier.verify(outcome.purchaseToken)
                    state.value =
                        if (verified.grantsAccess) {
                            PurchaseFlowStatus.Success
                        } else {
                            PurchaseFlow.failed(PurchaseFailureReason.InactivePurchase)
                        }
                }
                is PurchaseResult.Pending -> {
                    state.value = PurchaseFlowStatus.Verifying
                    val verified = verifier.verify(outcome.purchaseToken)
                    state.value =
                        if (verified.grantsAccess) PurchaseFlowStatus.Success else PurchaseFlowStatus.Pending
                }
                PurchaseResult.Canceled -> state.value = PurchaseFlowStatus.Idle
            }
        } catch (cancellation: CancellationException) {
            state.value = PurchaseFlowStatus.Idle
            throw cancellation
        } catch (_: Exception) {
            // The only awaited fallible step past Purchasing is verification.
            state.value = PurchaseFlow.failed(PurchaseFailureReason.Verification)
        }
    }

    /**
     * Reconciles an existing subscription when the route opens. This covers
     * renewals, interrupted purchase callbacks, and reinstall/device changes.
     * Only one preferred owned purchase is verified to avoid an old lower-tier
     * token overwriting a current higher-tier entitlement.
     */
    suspend fun reconcileOwnedPurchases() {
        if (!PurchaseFlow.canStart(state.value) || verifier == null) return
        try {
            state.value = PurchaseFlowStatus.Connecting
            if (billing.connect() != BillingConnectionResult.Connected) {
                state.value = PurchaseFlow.failed(PurchaseFailureReason.Connection)
                return
            }
            val purchase = preferredPurchaseForReconciliation(billing.queryOwnedPurchases())
            if (purchase == null) {
                state.value = PurchaseFlowStatus.Idle
                return
            }
            state.value = PurchaseFlowStatus.Verifying
            val verified = verifier.verify(purchase.purchaseToken)
            state.value =
                when {
                    verified.grantsAccess -> PurchaseFlowStatus.Success
                    purchase.state == OwnedPurchaseState.Pending -> PurchaseFlowStatus.Pending
                    else -> PurchaseFlow.failed(PurchaseFailureReason.InactivePurchase)
                }
        } catch (cancellation: CancellationException) {
            state.value = PurchaseFlowStatus.Idle
            throw cancellation
        } catch (_: Exception) {
            state.value = PurchaseFlow.failed(PurchaseFailureReason.Verification)
        }
    }

    fun reset() {
        state.value = PurchaseFlowStatus.Idle
    }
}
