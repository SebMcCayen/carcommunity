package com.kungsbackacarcommunity.app.subscription

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * Outcome surfaced by the billing layer after a purchase attempt. Either a
 * completed, acknowledged [Purchased] (carrying its verifiable purchaseToken) or
 * a [Canceled] signal (user dismissed the Play dialog or the purchase failed) so
 * the coordinator never hangs awaiting a token that will never arrive.
 */
sealed interface PurchaseResult {
    /** A completed, acknowledged purchase with its verifiable token. */
    data class Purchased(val purchaseToken: String) : PurchaseResult

    /** The purchase was canceled or failed; no token is available. */
    data object Canceled : PurchaseResult
}

/** Outcome of connecting the billing client. */
enum class BillingConnectionResult {
    Connected,
    Failed,
}

/** Outcome of querying the `member_monthly` product and its offer. */
enum class ProductQueryResult {
    Available,
    Unavailable,
}

/**
 * Play Billing access for the subscriptions slice (Phase 12 slice 24).
 * Firebase-free and Activity-only so the coordinator can be driven by a fake in
 * unit tests. The [PlayBillingRepository] implementation wraps `BillingClient`;
 * verification of a surfaced purchase happens separately via
 * [SubscriptionVerifier].
 */
interface BillingRepository {
    /** Starts the billing connection; suspends until connected or failed. */
    suspend fun connect(): BillingConnectionResult

    /** Queries the `member_monthly` SUBS product details. */
    suspend fun queryProduct(): ProductQueryResult

    /**
     * Launches the Play purchase UI for the queried product. Requires a real
     * [Activity]; results arrive asynchronously via [purchases].
     */
    fun launchPurchase(activity: Activity)

    /**
     * Emits the outcome of a launched purchase: [PurchaseResult.Purchased] with
     * a verifiable token, or [PurchaseResult.Canceled] when the user dismisses
     * the Play dialog / the purchase fails, so awaiters always complete.
     */
    val purchases: Flow<PurchaseResult>

    /** Releases the underlying billing connection. */
    fun close()
}
