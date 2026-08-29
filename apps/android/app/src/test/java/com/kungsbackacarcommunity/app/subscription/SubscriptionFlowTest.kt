package com.kungsbackacarcommunity.app.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFlowTest {

    @Test
    fun `only the two tier product ids are purchasable`() {
        assertEquals("plus_monthly", PLUS_MONTHLY_PRODUCT_ID)
        assertEquals("supporter_monthly", SUPPORTER_MONTHLY_PRODUCT_ID)
        assertEquals(setOf("plus_monthly", "supporter_monthly"), SUBSCRIPTION_PRODUCT_IDS)
        assertFalse("member_monthly" in SUBSCRIPTION_PRODUCT_IDS)
    }

    @Test
    fun `buildVerifyPayload sends google platform and the token`() {
        val payload = buildVerifyPayload("tok-123")
        assertEquals("google", payload["platform"])
        assertEquals("tok-123", payload["purchaseToken"])
        assertEquals(setOf("platform", "purchaseToken"), payload.keys)
    }

    @Test
    fun `obfuscated account id is deterministic sha256 without raw uid`() {
        val uid = "firebase-user-123"
        val hash = obfuscatedAccountIdForUid(uid)
        assertTrue(hash.matches(Regex("^[a-f0-9]{64}$")))
        assertEquals(
            "4ab08c5d68eeb18c08df44084fd659b3945ca897720de9a8bce5301bd7d2360d",
            hash,
        )
        assertFalse(hash.contains(uid))
        assertEquals(hash, obfuscatedAccountIdForUid(uid))
        assertFalse(hash == obfuscatedAccountIdForUid("different-user"))
    }

    @Test
    fun `verification response grants only paid active lifecycle states`() {
        for (status in listOf("active", "grace_period", "cancelled")) {
            assertTrue(
                parseVerificationResult(
                    mapOf(
                        "entitlement" to "member_monthly",
                        "status" to status,
                        "tier" to "supporter",
                    ),
                ).grantsAccess,
            )
        }
        assertFalse(
            parseVerificationResult(
                mapOf("entitlement" to "none", "status" to "expired", "tier" to "plus"),
            ).grantsAccess,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `verification response parser rejects malformed callable data`() {
        parseVerificationResult(mapOf("entitlement" to "member_monthly", "status" to "active"))
    }

    @Test
    fun `restore prefers purchased Supporter over lower or pending purchases`() {
        val plus =
            OwnedPurchase("plus", setOf(PLUS_MONTHLY_PRODUCT_ID), OwnedPurchaseState.Purchased)
        val supporter =
            OwnedPurchase(
                "supporter",
                setOf(SUPPORTER_MONTHLY_PRODUCT_ID),
                OwnedPurchaseState.Purchased,
            )
        val pendingSupporter =
            OwnedPurchase(
                "pending",
                setOf(SUPPORTER_MONTHLY_PRODUCT_ID),
                OwnedPurchaseState.Pending,
            )
        assertEquals(supporter, preferredPurchaseForReconciliation(listOf(plus, pendingSupporter, supporter)))
    }

    @Test
    fun `canStart is true only from settled states`() {
        assertTrue(PurchaseFlow.canStart(PurchaseFlowStatus.Idle))
        assertTrue(PurchaseFlow.canStart(PurchaseFlowStatus.Ready))
        assertTrue(PurchaseFlow.canStart(PurchaseFlowStatus.Success))
        assertTrue(PurchaseFlow.canStart(PurchaseFlowStatus.Pending))
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
