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
 * TO RE-LOCK, flip all FIVE switches together and redeploy:
 *   1. [ENABLED] = true                               (this file, + ship a build)
 *   2. functions/src/shared/memberGating.ts           MEMBER_GATING_ENABLED = true
 *   3. firebase/firestore.rules                       isActiveMember()
 *   4. firebase/storage.rules                         isActiveMember()
 *   5. firebase/database.rules.json                   liveLocation/$uid/latest
 *
 * functions/src/shared/memberGating.ts is the authoritative runbook and spells
 * each edit out. If the app and backend cannot ship together, re-lock THIS
 * switch first: an app that hides a working feature is recoverable, an app that
 * offers a failing one is not.
 *
 * WHAT THIS DOES TO UPSELL COPY — read before assuming messaging survives.
 *
 * The subscription screen DOES still report real entitlement: it is passed the
 * true `activeMember` value, not the gated one (see SubscriptionRoute's call
 * site in AuthenticatedApp — deliberately the one place that keeps the raw
 * flag).
 *
 * But in Events, Kronjakt and Partners the member copy IS the block, not a
 * hint beside it: each renders an upsell and then returns instead of the
 * content (`CrownHuntScreen` returns from the page, `PartnerDetailScreen`
 * returns from the column, `EventDetailScreen` shows the InfoCard in place of
 * the DetailCard). There is no way to keep that copy while unlocking access —
 * showing "membership required" over content the backend now serves would be
 * the same class of lie in the other direction. So while gating is disabled
 * those three screens show the feature instead of the upsell, by design.
 *
 * This is why the gated value is threaded as `passesMemberGate`, not
 * `isActiveMember`: those parameters carry a GATE RESULT, and they also decide
 * which Firestore flows to subscribe to (e.g. CrownHuntRoute). Conflating them
 * with the entitlement is what makes this easy to get wrong.
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
