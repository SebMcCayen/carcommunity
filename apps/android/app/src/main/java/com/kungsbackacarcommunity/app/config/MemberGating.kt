package com.kungsbackacarcommunity.app.config

/**
 * Legacy UI compatibility only. Keep ENABLED false. Approved free features use
 * explicit active-account access; paid check-in, attendee identities and partner
 * offers use narrow entitlement checks. Crown Hunt is free with tiered daily KP.
 * Never enable a blanket paywall to roll out those benefits. Backend rules remain
 * authoritative; see docs/play/subscription-access-rollout.md.
 */
object MemberGating {
    /**
     * The single switch for every member-gated UI affordance. `false` = show
     * and enable member features for any signed-in user.
     */
    const val ENABLED = false

    /**
     * Resolves a member-gated decision. While [ENABLED] is false this returns
     * true regardless of entitlement; flipping it back restores the exact
     * previous behaviour (`isActiveMember` passthrough) at every call site.
     *
     * Suspension/deletion are NOT handled here — the backend owns those, and
     * they remain enforced regardless of this switch.
     */
    fun allows(isActiveMember: Boolean): Boolean = !ENABLED || isActiveMember
}
