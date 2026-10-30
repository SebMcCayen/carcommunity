package com.kungsbackacarcommunity.app.profile

/** Public boolean projection only. Paid-period timing stays on the backend.
 * A cosmetic, never a membership/access proof. */
data class MembershipMapRing(
    val eligible: Boolean = false,
    val show: Boolean = true,
) {
    val visible: Boolean get() = eligible && show

    companion object {
        fun fromFields(eligible: Any?, show: Any?): MembershipMapRing = MembershipMapRing(
            eligible = eligible == true,
            show = show == null || show == true,
        )
    }
}
