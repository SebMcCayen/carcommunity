package com.kungsbackacarcommunity.app.subscription

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/** A completed, acknowledged purchase surfaced by the billing layer. */
data class PurchaseResult(val purchaseToken: String)

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

    /** Emits each acknowledged purchase (its verifiable purchaseToken). */
    val purchases: Flow<PurchaseResult>

    /** Releases the underlying billing connection. */
    fun close()
}
