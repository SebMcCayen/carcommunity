package com.kungsbackacarcommunity.app.profile

/** Public cosmetic projection, independent of the owner's saved preference. */
data class SupporterBadge(
    val eligible: Boolean = false,
    val show: Boolean = true,
) {
    val visible: Boolean get() = eligible && show

    companion object {
        fun fromFields(eligible: Any?, show: Any?): SupporterBadge =
            SupporterBadge(eligible == true, show == null || show == true)
    }
}
