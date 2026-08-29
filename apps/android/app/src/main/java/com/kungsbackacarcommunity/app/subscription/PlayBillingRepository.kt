package com.kungsbackacarcommunity.app.subscription

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
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
 * the other slices. The client never acknowledges purchases. It surfaces the
 * in-memory token to `subscription-verify`; the backend verifies and grants
 * before acknowledging server-side, which prevents forged/unverified purchases
 * from being finalized.
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

    private var cachedProducts: Map<String, ProductDetails> = emptyMap()

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
        if (billingClient.isReady) {
            BillingConnectionResult.Connected
        } else {
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

        }

    override suspend fun queryProducts(): ProductQueryResult =
        suspendCancellableCoroutine { continuation ->
            val params =
                QueryProductDetailsParams.newBuilder()
                    .setProductList(
                        SUBSCRIPTION_PRODUCT_IDS.map { productId ->
                            QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(productId)
                                .setProductType(BillingClient.ProductType.SUBS)
                                .build()
                        },
                    )
                    .build()
            billingClient.queryProductDetailsAsync(params) { result, productDetailsResult ->
                if (!continuation.isActive) return@queryProductDetailsAsync
                // Billing v8+ delivers a QueryProductDetailsResult (not a bare
                // List<ProductDetails>); the fetched products live under
                // productDetailsList, with unfetched ids reported separately.
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cachedProducts =
                        productDetailsResult.productDetailsList
                            .filter { product ->
                                product.productId in SUBSCRIPTION_PRODUCT_IDS &&
                                    product.subscriptionOfferDetails.orEmpty().any {
                                        it.basePlanId == MONTHLY_BASE_PLAN_ID
                                    }
                            }
                            .associateBy(ProductDetails::getProductId)
                    continuation.resume(ProductQueryResult(cachedProducts.keys))
                } else {
                    cachedProducts = emptyMap()
                    continuation.resume(ProductQueryResult(emptySet()))
                }
            }
        }

    override fun launchPurchase(
        activity: Activity,
        productId: String,
        obfuscatedAccountId: String,
    ): PurchaseLaunchResult {
        if (productId !in SUBSCRIPTION_PRODUCT_IDS) return PurchaseLaunchResult.Failed
        if (!isValidObfuscatedAccountId(obfuscatedAccountId)) {
            return PurchaseLaunchResult.Failed
        }
        val product = cachedProducts[productId] ?: return PurchaseLaunchResult.Failed
        val offerToken =
            product.subscriptionOfferDetails
                ?.firstOrNull { it.basePlanId == MONTHLY_BASE_PLAN_ID }
                ?.offerToken ?: return PurchaseLaunchResult.Failed
        val params =
            BillingFlowParams.newBuilder()
                .setObfuscatedAccountId(obfuscatedAccountId)
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product)
                            .setOfferToken(offerToken)
                            .build(),
                    ),
                )
                .build()
        val result = billingClient.launchBillingFlow(activity, params)
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                PurchaseLaunchResult.Launched
            } else {
                PurchaseLaunchResult.Failed
            }
    }

    override suspend fun queryOwnedPurchases(): List<OwnedPurchase> =
        suspendCancellableCoroutine { continuation ->
            val params =
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            billingClient.queryPurchasesAsync(params) { result, purchases ->
                if (!continuation.isActive) return@queryPurchasesAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(emptyList())
                    return@queryPurchasesAsync
                }
                continuation.resume(purchases.mapNotNull(::ownedPurchase))
            }
        }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.products.none { it in SUBSCRIPTION_PRODUCT_IDS }) {
            purchaseEvents.tryEmit(PurchaseResult.Canceled)
            return
        }
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED ->
                purchaseEvents.tryEmit(PurchaseResult.Purchased(purchase.purchaseToken))
            Purchase.PurchaseState.PENDING ->
                purchaseEvents.tryEmit(PurchaseResult.Pending(purchase.purchaseToken))
            else -> purchaseEvents.tryEmit(PurchaseResult.Canceled)
        }
    }

    private fun ownedPurchase(purchase: Purchase): OwnedPurchase? {
        val productIds = purchase.products.filterTo(mutableSetOf()) { it in SUBSCRIPTION_PRODUCT_IDS }
        if (productIds.isEmpty()) return null
        val state =
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> OwnedPurchaseState.Purchased
                Purchase.PurchaseState.PENDING -> OwnedPurchaseState.Pending
                else -> return null
            }
        return OwnedPurchase(
            purchaseToken = purchase.purchaseToken,
            productIds = productIds,
            state = state,
        )
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
