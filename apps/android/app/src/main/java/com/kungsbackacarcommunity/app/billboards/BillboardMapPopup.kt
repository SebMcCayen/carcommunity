package com.kungsbackacarcommunity.app.billboards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.MapDetailSheet

/** Test tag on the billboard popup opened by tapping a billboard marker. */
const val BILLBOARD_MAP_POPUP_TAG = "billboard_map_popup"

/** Test tag on the popup's call-to-action button. */
const val BILLBOARD_MAP_POPUP_CTA_TAG = "billboard_map_popup_cta"

/**
 * The popup opened by TAPPING a sponsored billboard marker on the map.
 *
 * Uses the map-owned reading sheet, keeping the map and its session composed
 * behind the detail. Reading long copy does not navigate to a separate page.
 *
 * ## What is reused from [BillboardsScreen], and what is not
 *
 * [BillboardBody] is the same composable the list cards render. The sheet title
 * uses the same localized sponsorship label as [BillboardSponsorLabel], before
 * any sales copy, so the disclosure stays visible even while the body scrolls.
 *
 * The CONTAINER is not shared, because that screen is a full-page list
 * (`AeroPage` + a column of cards) and this is a detail view over a live map.
 * Reusing the page would mean a tap on a marker replaces the map with a list
 * containing one item — losing both the map and the member's place on it, to
 * show an advert. The page stays a page and this stays a detail sheet.
 *
 * The sponsorship label is FIRST, above the headline, for the reason given on
 * [BillboardSponsorLabel].
 *
 * @param ctaLabel the call-to-action's label, or null when this billboard has
 *   no actionable CTA. Only `phone` and `website` produce one — the two the app
 *   can actually carry out, and the two the admin form collects a value for.
 */
@Composable
fun BillboardMapPopup(
    headline: String,
    message: String?,
    ctaLabel: String?,
    onCallToAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    MapDetailSheet(
        title = stringResource(R.string.billboard_sponsoredLabel),
        onDismiss = onDismiss,
        modifier = Modifier.testTag(BILLBOARD_MAP_POPUP_TAG),
        leadingContent = {
            Icon(Icons.Filled.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        },
    ) {
        BillboardBody(headline = headline, message = message)
        if (ctaLabel != null) {
            Button(
                onClick = onCallToAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = KccSpacing.s12).testTag(BILLBOARD_MAP_POPUP_CTA_TAG),
            ) {
                Text(text = ctaLabel)
            }
        }
    }
}
