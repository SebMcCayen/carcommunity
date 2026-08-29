package com.kungsbackacarcommunity.app.subscription

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SubscriptionCoordinatorTest {

    @Test
    fun `happy path reaches Success and verifies the surfaced token`() = runTest {
        val billing = FakeBilling()
        val verifier = FakeVerifier(shouldFail = false)
        val coordinator = SubscriptionCoordinator(billing, verifier)

        val job =
            launch {
                coordinator.subscribe(PLUS_MONTHLY_PRODUCT_ID) {
                    billing.emitPurchase("tok-abc")
                    PurchaseLaunchResult.Launched
                }
            }
        job.join()

        assertEquals(PurchaseFlowStatus.Success, coordinator.status.value)
        assertEquals("tok-abc", verifier.verifiedToken)
    }

    @Test
    fun `connection failure surfaces Connection reason`() = runTest {
        val billing = FakeBilling(connect = BillingConnectionResult.Failed)
        val coordinator = SubscriptionCoordinator(billing, FakeVerifier(shouldFail = false))

        coordinator.subscribe(PLUS_MONTHLY_PRODUCT_ID) {
            billing.emitPurchase("tok")
            PurchaseLaunchResult.Launched
        }

        assertEquals(
            PurchaseFlowStatus.Failed(PurchaseFailureReason.Connection),
            coordinator.status.value,
        )
    }

    @Test
    fun `unavailable product surfaces ProductUnavailable reason`() = runTest {
        val billing = FakeBilling(products = emptySet())
        val coordinator = SubscriptionCoordinator(billing, FakeVerifier(shouldFail = false))

        coordinator.subscribe(PLUS_MONTHLY_PRODUCT_ID) {
            billing.emitPurchase("tok")
            PurchaseLaunchResult.Launched
        }

        assertEquals(
            PurchaseFlowStatus.Failed(PurchaseFailureReason.ProductUnavailable),
            coordinator.status.value,
        )
    }

    @Test
    fun `verification failure surfaces Verification reason (fail-closed backend)`() = runTest {
        val billing = FakeBilling()
        val coordinator = SubscriptionCoordinator(billing, FakeVerifier(shouldFail = true))

        val job =
            launch {
                coordinator.subscribe(PLUS_MONTHLY_PRODUCT_ID) {
                    billing.emitPurchase("tok")
                    PurchaseLaunchResult.Launched
                }
            }
        job.join()

        assertEquals(
            PurchaseFlowStatus.Failed(PurchaseFailureReason.Verification),
            coordinator.status.value,
        )
    }

    @Test
    fun `no verifier surfaces Unavailable and never touches billing`() = runTest {
        val billing = FakeBilling()
        val coordinator = SubscriptionCoordinator(billing, verifier = null)

        coordinator.subscribe(PLUS_MONTHLY_PRODUCT_ID) {
            billing.emitPurchase("tok")
            PurchaseLaunchResult.Launched
        }

        assertEquals(
            PurchaseFlowStatus.Failed(PurchaseFailureReason.Unavailable),
            coordinator.status.value,
        )
        assertFalse(billing.connectCalled)
    }

    @Test
    fun `canceled purchase leaves Purchasing and returns to Idle`() = runTest {
        val billing = FakeBilling()
        val verifier = FakeVerifier(shouldFail = false)
        val coordinator = SubscriptionCoordinator(billing, verifier)

        val job =
            launch {
                coordinator.subscribe(PLUS_MONTHLY_PRODUCT_ID) {
                    billing.emitCanceled()
                    PurchaseLaunchResult.Launched
                }
            }
        job.join()

        assertEquals(PurchaseFlowStatus.Idle, coordinator.status.value)
        assertEquals(null, verifier.verifiedToken)
    }

    @Test
    fun `pending purchase is verified but remains pending without access`() = runTest {
        val billing = FakeBilling()
        val verifier = FakeVerifier(shouldFail = false, grantsAccess = false)
        val coordinator = SubscriptionCoordinator(billing, verifier)

        val job =
            launch {
                coordinator.subscribe(PLUS_MONTHLY_PRODUCT_ID) {
                    billing.emitPending("tok")
                    PurchaseLaunchResult.Launched
                }
            }
        job.join()

        assertEquals(PurchaseFlowStatus.Pending, coordinator.status.value)
        assertEquals("tok", verifier.verifiedToken)
    }

    @Test
    fun `route-open reconciliation restores supporter purchase`() = runTest {
        val billing =
            FakeBilling(
                owned =
                    listOf(
                        OwnedPurchase(
                            "supporter-token",
                            setOf(SUPPORTER_MONTHLY_PRODUCT_ID),
                            OwnedPurchaseState.Purchased,
                        ),
                    ),
            )
        val verifier = FakeVerifier(shouldFail = false)
        val coordinator = SubscriptionCoordinator(billing, verifier)

        coordinator.reconcileOwnedPurchases()

        assertEquals(PurchaseFlowStatus.Success, coordinator.status.value)
        assertEquals("supporter-token", verifier.verifiedToken)
    }

    @Test
    fun `reconciliation with no owned subscriptions stays idle and never verifies`() = runTest {
        val billing = FakeBilling(owned = emptyList())
        val verifier = FakeVerifier(shouldFail = false)
        val coordinator = SubscriptionCoordinator(billing, verifier)

        coordinator.reconcileOwnedPurchases()

        assertEquals(PurchaseFlowStatus.Idle, coordinator.status.value)
        assertEquals(null, verifier.verifiedToken)
    }

    @Test
    fun `synchronous launch failure does not wait for a purchase event`() = runTest {
        val billing = FakeBilling()
        val verifier = FakeVerifier(shouldFail = false)
        val coordinator = SubscriptionCoordinator(billing, verifier)

        coordinator.subscribe(PLUS_MONTHLY_PRODUCT_ID) { PurchaseLaunchResult.Failed }

        assertEquals(
            PurchaseFlowStatus.Failed(PurchaseFailureReason.Purchase),
            coordinator.status.value,
        )
        assertEquals(null, verifier.verifiedToken)
    }
}

private class FakeBilling(
    private val connect: BillingConnectionResult = BillingConnectionResult.Connected,
    private val products: Set<String> = SUBSCRIPTION_PRODUCT_IDS,
    private val owned: List<OwnedPurchase> = emptyList(),
) : BillingRepository {
    var connectCalled = false
        private set

    // No replay: the happy-path tests prove the coordinator subscribes before
    // the synchronous fake launch emits.
    private val events = MutableSharedFlow<PurchaseResult>(replay = 0, extraBufferCapacity = 4)
    override val purchases: Flow<PurchaseResult> = events

    fun emitPurchase(token: String) {
        events.tryEmit(PurchaseResult.Purchased(token))
    }

    fun emitCanceled() {
        events.tryEmit(PurchaseResult.Canceled)
    }

    fun emitPending(token: String) {
        events.tryEmit(PurchaseResult.Pending(token))
    }

    override suspend fun connect(): BillingConnectionResult {
        connectCalled = true
        return connect
    }

    override suspend fun queryProducts(): ProductQueryResult = ProductQueryResult(products)

    override fun launchPurchase(
        activity: android.app.Activity,
        productId: String,
        obfuscatedAccountId: String,
    ): PurchaseLaunchResult = PurchaseLaunchResult.Launched

    override suspend fun queryOwnedPurchases(): List<OwnedPurchase> = owned

    override fun close() = Unit
}

private class FakeVerifier(
    private val shouldFail: Boolean,
    private val grantsAccess: Boolean = true,
) : SubscriptionVerifier {
    var verifiedToken: String? = null
        private set

    override suspend fun verify(purchaseToken: String): SubscriptionVerificationResult {
        verifiedToken = purchaseToken
        if (shouldFail) throw IllegalStateException("verify failed closed")
        return if (grantsAccess) {
                SubscriptionVerificationResult("member_monthly", "active", "plus")
            } else {
                SubscriptionVerificationResult("none", "inactive", "plus")
            }
    }
}
