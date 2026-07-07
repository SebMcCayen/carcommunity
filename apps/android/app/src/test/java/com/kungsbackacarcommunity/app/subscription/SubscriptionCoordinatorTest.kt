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

        val job = launch { coordinator.subscribe { billing.emitPurchase("tok-abc") } }
        job.join()

        assertEquals(PurchaseFlowStatus.Success, coordinator.status.value)
        assertEquals("tok-abc", verifier.verifiedToken)
    }

    @Test
    fun `connection failure surfaces Connection reason`() = runTest {
        val billing = FakeBilling(connect = BillingConnectionResult.Failed)
        val coordinator = SubscriptionCoordinator(billing, FakeVerifier(shouldFail = false))

        coordinator.subscribe { billing.emitPurchase("tok") }

        assertEquals(
            PurchaseFlowStatus.Failed(PurchaseFailureReason.Connection),
            coordinator.status.value,
        )
    }

    @Test
    fun `unavailable product surfaces ProductUnavailable reason`() = runTest {
        val billing = FakeBilling(product = ProductQueryResult.Unavailable)
        val coordinator = SubscriptionCoordinator(billing, FakeVerifier(shouldFail = false))

        coordinator.subscribe { billing.emitPurchase("tok") }

        assertEquals(
            PurchaseFlowStatus.Failed(PurchaseFailureReason.ProductUnavailable),
            coordinator.status.value,
        )
    }

    @Test
    fun `verification failure surfaces Verification reason (fail-closed backend)`() = runTest {
        val billing = FakeBilling()
        val coordinator = SubscriptionCoordinator(billing, FakeVerifier(shouldFail = true))

        val job = launch { coordinator.subscribe { billing.emitPurchase("tok") } }
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

        coordinator.subscribe { billing.emitPurchase("tok") }

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

        val job = launch { coordinator.subscribe { billing.emitCanceled() } }
        job.join()

        assertEquals(PurchaseFlowStatus.Idle, coordinator.status.value)
        assertEquals(null, verifier.verifiedToken)
    }
}

private class FakeBilling(
    private val connect: BillingConnectionResult = BillingConnectionResult.Connected,
    private val product: ProductQueryResult = ProductQueryResult.Available,
) : BillingRepository {
    var connectCalled = false
        private set

    private val events = MutableSharedFlow<PurchaseResult>(replay = 1, extraBufferCapacity = 4)
    override val purchases: Flow<PurchaseResult> = events

    fun emitPurchase(token: String) {
        events.tryEmit(PurchaseResult.Purchased(token))
    }

    fun emitCanceled() {
        events.tryEmit(PurchaseResult.Canceled)
    }

    override suspend fun connect(): BillingConnectionResult {
        connectCalled = true
        return connect
    }

    override suspend fun queryProduct(): ProductQueryResult = product

    override fun launchPurchase(activity: android.app.Activity) = Unit

    override fun close() = Unit
}

private class FakeVerifier(private val shouldFail: Boolean) : SubscriptionVerifier {
    var verifiedToken: String? = null
        private set

    override suspend fun verify(purchaseToken: String) {
        verifiedToken = purchaseToken
        if (shouldFail) throw IllegalStateException("verify failed closed")
    }
}
