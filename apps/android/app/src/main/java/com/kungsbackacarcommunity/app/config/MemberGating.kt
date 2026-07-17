package com.kungsbackacarcommunity.app.config

/**
 * THE MEMBER-GATING SWITCH (Android UI layer).
 *
 * Subscriber gating is currently DISABLED so every feature is reachable in the
 * app by an account without the `activeMember` entitlement. No gating code was
 * deleted — it is bypassed by [ENABLED], and re-locking flips one value.
 *
 * This is the CLIENT half only. The UI is not a security boundary: the backend
 * (Cloud Functions callables + Firestore/Storage/RTDB rules) has its own
 * switches, and re-locking the app without re-locking those leaves the features
 * open, while re-locking the backend without the app produces the exact bug
 * class this change fixes — a screen that offers an action the server refuses.
 *
 * TO RE-LOCK, flip all four switches together and redeploy:
 *   1. [ENABLED] = true                               (this file)
 *   2. functions/src/shared/memberGating.ts           MEMBER_GATING_ENABLED = true
 *   3. firebase/firestore.rules + firebase/storage.rules   isActiveMember()
 *   4. firebase/database.rules.json                   liveLocation/$uid/latest
 *
 * Member *messaging* is deliberately untouched: the subscription screen still
 * reports real entitlement (it is passed the true `activeMember` value, not the
 * gated one) and upsell copy elsewhere still renders. Only the blocking stops.
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
