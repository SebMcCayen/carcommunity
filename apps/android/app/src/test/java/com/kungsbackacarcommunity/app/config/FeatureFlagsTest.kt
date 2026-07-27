package com.kungsbackacarcommunity.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTest {
    @Test
    fun `defaults match the contract`() {
        assertTrue(FeatureFlags.DEFAULTS.isEnabled(FeatureFlag.LIVE_LOCATION))
        assertTrue(FeatureFlags.DEFAULTS.isEnabled(FeatureFlag.CHAT))
        assertFalse(FeatureFlags.DEFAULTS.isEnabled(FeatureFlag.PARTNER_INSIGHTS_PASS_BY))
        // Kronjakt as a whole is on; its AUTO-SPAWN half is not. The split
        // matters: a hand-placed point carries an admin's confirmation that its
        // spot is safe to stop at, an auto-placed crown cannot, so the automatic
        // half stays dark until an operator deliberately enables it.
        assertTrue(FeatureFlags.DEFAULTS.isEnabled(FeatureFlag.CROWN_HUNT))
        assertFalse(FeatureFlags.DEFAULTS.isEnabled(FeatureFlag.CROWN_HUNT_SPAWN))
    }

    /**
     * The keys are the contract's, verbatim. A typo here would silently read the
     * contract default forever — which for `crownHuntSpawn` means a feature that
     * could never be switched on, with no error anywhere to say why.
     */
    @Test
    fun `flag keys match the contract registry`() {
        assertEquals("crownHunt", FeatureFlag.CROWN_HUNT.key)
        assertEquals("crownHuntSpawn", FeatureFlag.CROWN_HUNT_SPAWN.key)
        val stored = FeatureFlags.fromStored(mapOf("crownHuntSpawn" to true))
        assertTrue(stored.isEnabled(FeatureFlag.CROWN_HUNT_SPAWN))
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
