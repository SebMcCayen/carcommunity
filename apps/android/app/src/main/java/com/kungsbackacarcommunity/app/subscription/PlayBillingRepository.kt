package com.kungsbackacarcommunity.app.subscription

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.ProductDetails
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [BillingRepository] backed by Google Play `BillingClient` (Phase 12 slice 24).
 *
 * Construction is guarded ([createIfAvailable]) so config-less / no-Play builds
 * compile and launch: `BillingClient` needs no Firebase and no
 * `google-services.json`, but we still centralise creation so the wiring mirrors
 * the other slices. On a `PURCHASED` purchase we acknowledge it (subscriptions
 * must be acknowledged within 3 days or they auto-refund) and surface the
 * purchaseToken for the coordinator to hand to `subscription-verify`.
 *
 * NOTE: an actual charge requires a Play Console `member_monthly` subscription
 * product and a signed build uploaded to a Play track — neither exists yet, so
 * this path is exercised only structurally here (deferred cutover).
 */
class PlayBillingRepository private constructor(
    context: Context,
) : BillingRepository {

    private val purchaseEvents =
        MutableSharedFlow<PurchaseResult>(
            replay = 0,
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val purchases: Flow<PurchaseResult> = purchaseEvents.asSharedFlow()

    private var cachedProduct: ProductDetails? = null

    private val purchasesListener =
        PurchasesUpdatedListener { result, purchases ->
            // On OK with purchases, surface each. On any other outcome
            // (USER_CANCELED, other failure codes, or a null list on OK) emit a
            // Canceled signal so a coordinator awaiting the flow always completes.
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                purchases.forEach { handlePurchase(it) }
            } else {
                purchaseEvents.tryEmit(PurchaseResult.Canceled)
            }
        }

    private val billingClient: BillingClient =
        BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()

    override suspend fun connect(): BillingConnectionResult =
        suspendCancellableCoroutine { continuation ->
            billingClient.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (!continuation.isActive) return
                        val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                        continuation.resume(
                            if (ok) BillingConnectionResult.Connected else BillingConnectionResult.Failed,
                        )
                    }

                    override fun onBillingServiceDisconnected() {
                        // Left to the caller to re-connect on the next attempt.
                    }
                },
            )
        }

    override suspend fun queryProduct(): ProductQueryResult =
        suspendCancellableCoroutine { continuation ->
            val params =
                QueryProductDetailsParams.newBuilder()
                    .setProductList(
                        listOf(
                            QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(SUBSCRIPTION_PRODUCT)
                                .setProductType(BillingClient.ProductType.SUBS)
                                .build(),
                        ),
                    )
                    .build()
            billingClient.queryProductDetailsAsync(params) { result, productDetailsResult ->
                if (!continuation.isActive) return@queryProductDetailsAsync
                // Billing v8+ delivers a QueryProductDetailsResult (not a bare
                // List<ProductDetails>); the fetched products live under
                // productDetailsList, with unfetched ids reported separately.
                val product = productDetailsResult.productDetailsList.firstOrNull()
                if (result.responseCode == BillingClient.BillingResponseCode.OK && product != null) {
                    cachedProduct = product
                    continuation.resume(ProductQueryResult.Available)
                } else {
                    continuation.resume(ProductQueryResult.Unavailable)
                }
            }
        }

    override fun launchPurchase(activity: Activity) {
        val product = cachedProduct ?: return
        val offerToken =
            product.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return
        val params =
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product)
                            .setOfferToken(offerToken)
                            .build(),
                    ),
                )
                .build()
        billingClient.launchBillingFlow(activity, params)
    }

    private fun handlePurchase(purchase: Purchase) {
        // A non-PURCHASED state (e.g. PENDING) grants no entitlement; treat it as
        // a Canceled outcome so an awaiting coordinator leaves its purchasing
        // state instead of hanging for a token that will not arrive here.
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            purchaseEvents.tryEmit(PurchaseResult.Canceled)
            return
        }
        if (purchase.isAcknowledged) {
            purchaseEvents.tryEmit(PurchaseResult.Purchased(purchase.purchaseToken))
            return
        }
        val ackParams =
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        billingClient.acknowledgePurchase(ackParams) {
            // Surface the token regardless of ack result — verification is the
            // source of truth for entitlement; ack just prevents auto-refund.
            purchaseEvents.tryEmit(PurchaseResult.Purchased(purchase.purchaseToken))
        }
    }

    override fun close() {
        billingClient.endConnection()
    }

    companion object {
        /**
         * Creates a Play Billing repository. Returns null when the platform has
         * no usable Play/Billing surface so the app can hide the entry point and
         * still launch (config-less guard, mirrors the Firebase createIfAvailable
         * pattern used by the other slices).
         */
        fun createIfAvailable(context: Context): BillingRepository? =
            try {
                PlayBillingRepository(context)
            } catch (_: Throwable) {
                null
            }
    }
}
