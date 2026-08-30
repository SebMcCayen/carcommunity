package com.kungsbackacarcommunity.app.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionStateRepositoryTest {
    @Test
    fun `active google Plus record restores tier and product without a token`() {
        val record =
            parseStoredSubscription(
                tier = "plus",
                status = "active",
                entitlement = "member_monthly",
                platform = "google",
            )

        requireNotNull(record)
        assertTrue(record.grantsAccess)
        assertEquals(PLUS_MONTHLY_PRODUCT_ID, record.googleProductId)
    }

    @Test
    fun `cancelled before expiry still grants access`() {
        val record =
            parseStoredSubscription(
                tier = "supporter",
                status = "cancelled",
                entitlement = "member_monthly",
                platform = "google",
            )

        requireNotNull(record)
        assertTrue(record.grantsAccess)
        assertEquals(SUPPORTER_MONTHLY_PRODUCT_ID, record.googleProductId)
    }

    @Test
    fun `expired record retains historical tier but grants no access`() {
        val record =
            parseStoredSubscription(
                tier = "supporter",
                status = "expired",
                entitlement = "none",
                platform = "google",
            )

        requireNotNull(record)
        assertFalse(record.grantsAccess)
    }

    @Test
    fun `malformed paid community record is rejected`() {
        assertNull(
            parseStoredSubscription(
                tier = "community",
                status = "active",
                entitlement = "member_monthly",
                platform = "google",
            ),
        )
    }
}
