package com.kungsbackacarcommunity.app.subscription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Orchestrates the subscription purchase → verify flow (Phase 12 slice 24):
 * connect → query product → launch Play purchase → await the acknowledged
 * purchaseToken → call `subscription-verify`. Pure Kotlin (the Play-UI launch is
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
     * Runs the full flow. [launchPurchase] triggers the Play purchase UI (the
     * route supplies `{ billing.launchPurchase(activity) }`). Suspends until the
     * purchase is surfaced and verified (or the flow fails). Guards against
     * re-entry while a flow is in flight.
     */
    suspend fun subscribe(launchPurchase: () -> Unit) {
        if (!PurchaseFlow.canStart(state.value)) return
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
            if (billing.queryProduct() != ProductQueryResult.Available) {
                state.value = PurchaseFlow.failed(PurchaseFailureReason.ProductUnavailable)
                return
            }
            state.value = PurchaseFlowStatus.Ready

            state.value = PurchaseFlowStatus.Purchasing
            launchPurchase()
            // Await the first purchase outcome. A cancellation (user dismissed
            // the Play dialog, or the purchase failed) leaves the Purchasing
            // state back to Idle instead of hanging forever.
            when (val outcome = billing.purchases.first()) {
                is PurchaseResult.Purchased -> {
                    state.value = PurchaseFlowStatus.Verifying
                    verifier.verify(outcome.purchaseToken)
                    state.value = PurchaseFlowStatus.Success
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

    fun reset() {
        state.value = PurchaseFlowStatus.Idle
    }
}
