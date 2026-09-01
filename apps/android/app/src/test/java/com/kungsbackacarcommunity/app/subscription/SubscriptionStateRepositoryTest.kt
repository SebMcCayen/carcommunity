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
        assertEquals(5, record.garageVehicleLimit)
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
        assertEquals(10, record.garageVehicleLimit)
    }

    @Test
    fun `legacy paid record without tier resolves to Plus`() {
        val record =
            parseStoredSubscription(
                tier = null,
                status = "active",
                entitlement = "member_monthly",
                platform = "google",
            )

        requireNotNull(record)
        assertEquals("plus", record.tier)
        assertEquals(EffectiveSubscriptionTier.PLUS, record.effectiveTier)
        assertEquals(5, record.garageVehicleLimit)
    }

    @Test
    fun `isPaidSubscriber is true for a paid tier and false for community`() {
        // Event-details gate (Slice D) reads this: a Plus or Supporter record is
        // a paying member; a null/expired/community record is not.
        val plus =
            parseStoredSubscription(
                tier = "plus",
                status = "active",
                entitlement = "member_monthly",
                platform = "google",
            )
        val supporter =
            parseStoredSubscription(
                tier = "supporter",
                status = "active",
                entitlement = "member_monthly",
                platform = "google",
            )
        val expired =
            parseStoredSubscription(
                tier = "supporter",
                status = "expired",
                entitlement = "none",
                platform = "google",
            )

        assertTrue(plus.isPaidSubscriber)
        assertTrue(supporter.isPaidSubscriber)
        assertFalse(expired.isPaidSubscriber)
        // A member who has never subscribed (no record) is not paid.
        assertFalse((null as StoredSubscription?).isPaidSubscriber)
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
        assertEquals(EffectiveSubscriptionTier.COMMUNITY, record.effectiveTier)
        assertEquals(2, record.garageVehicleLimit)
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
        assertEquals(2, null.garageVehicleLimit)
    }
}
