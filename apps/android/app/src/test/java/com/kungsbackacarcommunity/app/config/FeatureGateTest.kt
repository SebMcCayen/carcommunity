package com.kungsbackacarcommunity.app.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureGateTest {
    @Test
    fun `non-member-gated feature follows the flag only`() {
        assertTrue(
            FeatureGate.isAvailable(FeatureFlags.DEFAULTS, FeatureFlag.CHAT, memberGated = false, isActiveMember = false),
        )
    }

    @Test
    fun `member-gated feature requires membership`() {
        val flags = FeatureFlags.DEFAULTS
        assertFalse(
            FeatureGate.isAvailable(flags, FeatureFlag.LIVE_LOCATION, memberGated = true, isActiveMember = false),
        )
        assertTrue(
            FeatureGate.isAvailable(flags, FeatureFlag.LIVE_LOCATION, memberGated = true, isActiveMember = true),
        )
    }

    @Test
    fun `a disabled flag is never available even for members`() {
        val flags = FeatureFlags.fromStored(mapOf("liveLocation" to false))
        assertFalse(
            FeatureGate.isAvailable(flags, FeatureFlag.LIVE_LOCATION, memberGated = true, isActiveMember = true),
        )
    }
}
