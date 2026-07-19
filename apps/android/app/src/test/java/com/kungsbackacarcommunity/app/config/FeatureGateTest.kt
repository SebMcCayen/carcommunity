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
    fun `member-gated feature admits non-members while member gating is disabled`() {
        // Was: `member-gated feature requires membership` (assertFalse for a
        // non-member). MemberGating.ENABLED is false, so the member term is
        // satisfied by everyone. Re-locking restores the requirement.
        val flags = FeatureFlags.DEFAULTS
        assertTrue(
            FeatureGate.isAvailable(flags, FeatureFlag.LIVE_LOCATION, memberGated = true, isActiveMember = false),
        )
        assertTrue(
            FeatureGate.isAvailable(flags, FeatureFlag.LIVE_LOCATION, memberGated = true, isActiveMember = true),
        )
    }

    @Test
    fun `a disabled flag is never available, member or not, gated or not`() {
        // Teeth: the unlock must NOT reach the feature-flag term. A server
        // -disabled flag still hides the feature from everyone.
        val flags = FeatureFlags.fromStored(mapOf("liveLocation" to false))
        assertFalse(
            FeatureGate.isAvailable(flags, FeatureFlag.LIVE_LOCATION, memberGated = true, isActiveMember = true),
        )
        assertFalse(
            FeatureGate.isAvailable(flags, FeatureFlag.LIVE_LOCATION, memberGated = true, isActiveMember = false),
        )
        assertFalse(
            FeatureGate.isAvailable(flags, FeatureFlag.LIVE_LOCATION, memberGated = false, isActiveMember = true),
        )
    }
}

class MemberGatingTest {
    @Test
    fun `gating is currently disabled`() {
        // Flipping MemberGating.ENABLED back to true re-locks the UI layer.
        // The FOUR backend switches (callables + firestore/storage/database
        // rules) must be flipped with it — see config/MemberGating.kt, and
        // functions/src/shared/memberGating.ts for the authoritative runbook.
        assertFalse(MemberGating.ENABLED)
    }

    @Test
    fun `allows returns true for members and non-members alike while gating is disabled`() {
        assertTrue(MemberGating.allows(isActiveMember = false))
        assertTrue(MemberGating.allows(isActiveMember = true))
    }
}
