package com.kungsbackacarcommunity.app.config

/**
 * Pure feature-availability decisions (Phase 12 slice 3) that later feature
 * slices reuse, combining the feature flag with the member entitlement.
 */
object FeatureGate {
    /**
     * A feature is available when its flag is enabled and — for member-gated
     * features — the user passes the member gate. The backend still enforces
     * both independently; this only drives what the UI offers.
     *
     * Member gating is currently DISABLED via [MemberGating], so the
     * `memberGated` term is presently satisfied by every signed-in user. The
     * FLAG term is unaffected: a server-disabled flag still hides the feature.
     */
    fun isAvailable(
        flags: FeatureFlags,
        flag: FeatureFlag,
        memberGated: Boolean,
        isActiveMember: Boolean,
    ): Boolean = flags.isEnabled(flag) && (!memberGated || MemberGating.allows(isActiveMember))
}
