package com.kungsbackacarcommunity.app.billboards

import androidx.annotation.StringRes
import com.kungsbackacarcommunity.app.R

/**
 * A billboard's call-to-action, resolved to something the app can actually do.
 *
 * @property labelRes the button label.
 * @property uri what to hand `Intent.ACTION_VIEW`.
 * @property interactionType what to report to `billboards-recordInteraction`, so
 *   the partner's insights distinguish a call from a website visit.
 */
data class BillboardAction(
    @StringRes val labelRes: Int,
    val uri: String,
    val interactionType: BillboardInteractionType,
)

/**
 * Maps a billboard's stored CTA pair onto an action the popup can offer.
 *
 * Only `phone` and `website` resolve. That is not an omission — it mirrors the
 * admin form, which collects a `callToActionValue` for exactly those two and
 * for nothing else, so the other three enum members are stored with a null
 * value and there is no target to open. Offering a dead button for them would
 * be worse than offering none.
 *
 * Specifically NOT resolved:
 *  - `navigate` would need the billboard's own coordinates as a destination,
 *    which is a route request into the shell's navigation stack rather than an
 *    external intent, and routing a driver somewhere because they tapped an
 *    advert is a decision that wants its own review.
 *  - `partner_profile` and `offer_view` point at in-app destinations that
 *    billboards have no navigation entry to reach.
 *
 * Pure, so the URI construction and the "no action" cases are unit-tested off
 * the composable.
 */
object BillboardCallToAction {
    /** The action [billboard] offers, or null when it offers none. */
    fun resolve(billboard: Billboard): BillboardAction? {
        val value = billboard.callToActionValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (billboard.callToActionType) {
            BillboardCtaType.PHONE ->
                BillboardAction(
                    labelRes = R.string.billboard_call,
                    // `tel:` with ACTION_VIEW opens the dialler PRE-FILLED rather
                    // than placing the call, so tapping never dials by itself —
                    // the member still presses the call button. That also avoids
                    // needing CALL_PHONE, a permission this app has no business
                    // holding for an advert.
                    uri = "tel:${value.filter { it.isDigit() || it == '+' }}",
                    interactionType = BillboardInteractionType.PHONE,
                )

            BillboardCtaType.WEBSITE ->
                BillboardAction(
                    labelRes = R.string.billboard_visitWebsite,
                    // A stored value with no scheme is a hostname, and handing
                    // ACTION_VIEW a schemeless string resolves to nothing at
                    // all. Default to https rather than http: this link is
                    // opened from an advert, so the weaker default is not one to
                    // fall back to silently.
                    uri = if (value.contains("://")) value else "https://$value",
                    interactionType = BillboardInteractionType.WEBSITE,
                )

            else -> null
        }
    }
}
