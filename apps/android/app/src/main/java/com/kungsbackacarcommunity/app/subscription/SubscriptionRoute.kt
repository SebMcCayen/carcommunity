package com.kungsbackacarcommunity.app.subscription

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.launch

/**
 * Subscription integration route (Phase 12 slice 24): wires the billing
 * repository + verifier into a [SubscriptionCoordinator] and drives the screen.
 *
 * Launching Play Billing needs an [Activity]; we resolve it from the local
 * context and disable the subscribe button when it is null (or when billing is
 * unavailable on this build). The billing repository is a single shared instance
 * created in MainActivity and reused across screen openings, so this route does
 * NOT close it on dispose — endConnection here would leave the reused client
 * unusable on re-entry. Its lifecycle is owned by the Activity/app; the
 * coordinator (re)connects on demand.
 */
@Composable
fun SubscriptionRoute(
    billing: BillingRepository,
    verifier: SubscriptionVerifier?,
    stateRepository: SubscriptionStateRepository?,
    uid: String,
    isActiveMember: Boolean,
    onBack: () -> Unit,
) {
    val activity: Activity? = LocalActivity.current
    val context = LocalContext.current
    val managementUnavailableMessage =
        stringResource(R.string.subscription_manageUnavailable)
    val coordinator = remember(billing, verifier) { SubscriptionCoordinator(billing, verifier) }
    val status by coordinator.status.collectAsState()
    val ownedPurchase by coordinator.ownedPurchase.collectAsState()
    val subscriptionFlow =
        remember(stateRepository, uid) {
            stateRepository?.observeSubscription(uid) ?: kotlinx.coroutines.flow.flowOf(null)
        }
    val storedSubscription by subscriptionFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val obfuscatedAccountId = remember(uid) { obfuscatedAccountIdForUid(uid) }
    val verifiedTier = (status as? PurchaseFlowStatus.Success)?.tier
    val storedPlaySubscription =
        storedSubscription?.takeIf { it.grantsAccess && it.platform == "google" }
    // Android can only manage Google Play subscriptions. Manual/admin grants
    // and future Apple entitlements remain valid membership access through the
    // users flag, but must not be presented as Play-owned products.
    val currentTier = verifiedTier ?: storedPlaySubscription?.tier
    val ownedProductId = productIdForOwnedPurchase(ownedPurchase)
    val currentProductId =
        when (currentTier) {
            "plus" -> PLUS_MONTHLY_PRODUCT_ID
            "supporter" -> SUPPORTER_MONTHLY_PRODUCT_ID
            else -> storedPlaySubscription?.googleProductId
        }
    val canManageSubscription =
        verifiedTier != null || storedPlaySubscription != null || ownedProductId != null
    // A replacement must point at the actual Play-owned current product. A
    // deferred downgrade can temporarily report a target purchase while the
    // backend correctly retains Supporter; block another change in that window.
    val canChangePlan =
        status is PurchaseFlowStatus.Success &&
        ownedPurchase?.state == OwnedPurchaseState.Purchased &&
            ownedProductId != null &&
            ownedProductId == currentProductId

    // Restore/reconcile on every route entry. Play owns the purchase history;
    // no raw token is persisted locally, and reinstall/renewal needs no checkout.
    LaunchedEffect(coordinator, uid) { coordinator.reconcileOwnedPurchases() }

    SubscriptionScreen(
        isActiveMember = isActiveMember,
        currentTier = currentTier,
        status = status,
        canSubscribe = activity != null && verifier != null,
        canChangePlan = canChangePlan,
        canManageSubscription = canManageSubscription,
        onSubscribe = { productId ->
            activity?.let { a ->
                scope.launch {
                    val replacement =
                        ownedPurchase
                            ?.takeIf {
                                it.state == OwnedPurchaseState.Purchased &&
                                    ownedProductId != null &&
                                    ownedProductId != productId &&
                                    ownedProductId == currentProductId
                            }
                            ?.let {
                                SubscriptionReplacement(
                                    oldPurchaseToken = it.purchaseToken,
                                    oldProductId = requireNotNull(ownedProductId),
                                )
                            }
                    coordinator.subscribe(productId) {
                        billing.launchPurchase(
                            a,
                            productId,
                            obfuscatedAccountId,
                            replacement,
                        )
                    }
                }
            }
        },
        onManageSubscription = { productId ->
            SubscriptionManagementLink.open(
                context = context,
                applicationId = context.packageName,
                productId = productId,
                onUnavailable = {
                    Toast.makeText(
                        context,
                        managementUnavailableMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        },
        onBack = onBack,
    )
}
