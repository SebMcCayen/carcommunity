package com.kungsbackacarcommunity.app.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTest {
    @Test
    fun `defaults match the contract`() {
        assertTrue(FeatureFlags.DEFAULTS.isEnabled(FeatureFlag.LIVE_LOCATION))
        assertTrue(FeatureFlags.DEFAULTS.isEnabled(FeatureFlag.CHAT))
        assertFalse(FeatureFlags.DEFAULTS.isEnabled(FeatureFlag.PARTNER_INSIGHTS_PASS_BY))
    }

    @Test
    fun `fromStored overlays booleans and keeps missing at default`() {
        val flags = FeatureFlags.fromStored(mapOf("chat" to false, "partnerInsightsPassBy" to true))
        assertFalse(flags.isEnabled(FeatureFlag.CHAT))
        assertTrue(flags.isEnabled(FeatureFlag.PARTNER_INSIGHTS_PASS_BY))
        assertTrue(flags.isEnabled(FeatureFlag.LIVE_LOCATION)) // absent → default true
    }

    @Test
    fun `fromStored ignores non-boolean and unknown values`() {
        val flags = FeatureFlags.fromStored(mapOf("chat" to "nope", "bogusFlag" to true))
        assertTrue(flags.isEnabled(FeatureFlag.CHAT)) // non-boolean → default
    }
}
