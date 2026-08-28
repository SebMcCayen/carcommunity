package com.kungsbackacarcommunity.app.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFlowTest {

    @Test
    fun `product id is member_monthly`() {
        assertEquals("member_monthly", SUBSCRIPTION_PRODUCT)
    }

    @Test
    fun `tier product ids are immutable monthly ids without changing legacy runtime`() {
        assertEquals("plus_monthly", PLUS_MONTHLY_PRODUCT_ID)
        assertEquals("supporter_monthly", SUPPORTER_MONTHLY_PRODUCT_ID)
        assertEquals("member_monthly", SUBSCRIPTION_PRODUCT)
    }

    @Test
    fun `buildVerifyPayload sends google platform and the token`() {
        val payload = buildVerifyPayload("tok-123")
        assertEquals("google", payload["platform"])
        assertEquals("tok-123", payload["purchaseToken"])
        assertEquals(setOf("platform", "purchaseToken"), payload.keys)
    }

    @Test
    fun `canStart is true only from settled states`() {
        assertTrue(PurchaseFlow.canStart(PurchaseFlowStatus.Idle))
        assertTrue(PurchaseFlow.canStart(PurchaseFlowStatus.Ready))
        assertTrue(PurchaseFlow.canStart(PurchaseFlowStatus.Success))
        assertTrue(PurchaseFlow.canStart(PurchaseFlowStatus.Failed(PurchaseFailureReason.Connection)))

        assertFalse(PurchaseFlow.canStart(PurchaseFlowStatus.Connecting))
        assertFalse(PurchaseFlow.canStart(PurchaseFlowStatus.Purchasing))
        assertFalse(PurchaseFlow.canStart(PurchaseFlowStatus.Verifying))
    }

    @Test
    fun `isInFlight is the inverse of canStart`() {
        assertTrue(PurchaseFlow.isInFlight(PurchaseFlowStatus.Verifying))
        assertFalse(PurchaseFlow.isInFlight(PurchaseFlowStatus.Idle))
    }
}
