package com.kungsbackacarcommunity.app.config

/**
 * Pure feature-availability decisions (Phase 12 slice 3) that later feature
 * slices reuse, combining the feature flag with the member entitlement.
 */
object FeatureGate {
    /**
     * A feature is available when its flag is enabled and — for member-gated
     * features — the user holds an active member entitlement. The backend
     * still enforces both independently; this only drives what the UI offers.
     */
    fun isAvailable(
        flags: FeatureFlags,
        flag: FeatureFlag,
        memberGated: Boolean,
        isActiveMember: Boolean,
    ): Boolean = flags.isEnabled(flag) && (!memberGated || isActiveMember)
}
