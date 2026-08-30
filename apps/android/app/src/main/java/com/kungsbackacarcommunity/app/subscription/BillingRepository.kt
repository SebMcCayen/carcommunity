package com.kungsbackacarcommunity.app.subscription

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * Outcome surfaced by the billing layer after a purchase attempt. The client
 * never acknowledges a purchase; it sends the token to the secure backend,
 * which verifies, grants, and then acknowledges in that order.
 */
sealed interface PurchaseResult {
    /** A completed Play purchase carrying the token for server verification. */
    data class Purchased(val purchaseToken: String) : PurchaseResult

    /** Payment is still pending; no entitlement or acknowledgement is allowed. */
    data class Pending(val purchaseToken: String) : PurchaseResult

    /** The purchase was canceled or failed; no token is available. */
    data object Canceled : PurchaseResult
}

/** Outcome of connecting the billing client. */
enum class BillingConnectionResult {
    Connected,
    Failed,
}

/** Product ids Play returned from the Plus + Supporter query. */
data class ProductQueryResult(val availableProductIds: Set<String>)

enum class PurchaseLaunchResult {
    Launched,
    Failed,
}

enum class OwnedPurchaseState {
    Purchased,
    Pending,
}

/** A purchase restored from Play; the token remains in memory only. */
data class OwnedPurchase(
    val purchaseToken: String,
    val productIds: Set<String>,
    val state: OwnedPurchaseState,
)

/** Existing Play purchase that a new tier purchase replaces. Kept in memory only. */
data class SubscriptionReplacement(
    val oldPurchaseToken: String,
    val oldProductId: String,
)

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

    /** Queries both `plus_monthly` and `supporter_monthly` SUBS products. */
    suspend fun queryProducts(): ProductQueryResult

    /**
     * Launches the Play purchase UI for the queried product. Requires a real
     * [Activity]; results arrive asynchronously via [purchases].
     */
    fun launchPurchase(
        activity: Activity,
        productId: String,
        obfuscatedAccountId: String,
        replacement: SubscriptionReplacement? = null,
    ): PurchaseLaunchResult

    /** Restores owned/pending subscriptions for renewal and reinstall reconciliation. */
    suspend fun queryOwnedPurchases(): List<OwnedPurchase>

    /**
     * Emits the outcome of a launched purchase: [PurchaseResult.Purchased] with
     * a verifiable token, or [PurchaseResult.Canceled] when the user dismisses
     * the Play dialog / the purchase fails, so awaiters always complete.
     */
    val purchases: Flow<PurchaseResult>

    /** Releases the underlying billing connection. */
    fun close()
}
