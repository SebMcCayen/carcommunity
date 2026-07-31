package com.kungsbackacarcommunity.app.billboards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccAlpha
import com.kungsbackacarcommunity.app.design.KccRadius

/** Test tag on the billboard popup opened by tapping a billboard marker. */
const val BILLBOARD_MAP_POPUP_TAG = "billboard_map_popup"

/** Test tag on the popup's call-to-action button. */
const val BILLBOARD_MAP_POPUP_CTA_TAG = "billboard_map_popup_cta"

/**
 * The popup opened by TAPPING a sponsored billboard marker on the map.
 *
 * Modelled on `EventMarkerInfoPopup` — same Popup-over-the-map treatment, same
 * translucent aero surface, same close affordance — because a member tapping a
 * pin should get the same kind of thing whatever the pin was. It is a POPUP and
 * not a full screen deliberately: an advert is never allowed to take the map
 * away from the person reading it.
 *
 * ## What is reused from [BillboardsScreen], and what is not
 *
 * The CONTENT is shared, not copied: [BillboardSponsorLabel] and
 * [BillboardBody] are the same composables that screen's cards render, so an
 * advert looks like one thing however a member reached it and a change to how
 * sponsored copy is presented is a single edit.
 *
 * The CONTAINER is not shared, because that screen is a full-page list
 * (`AeroPage` + a column of cards) and this is a detail view over a live map.
 * Reusing the page would mean a tap on a marker replaces the map with a list
 * containing one item — losing both the map and the member's place on it, to
 * show an advert. So the page stays a page and this stays a popup, exactly like
 * the event pins on the same surface.
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
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .testTag(BILLBOARD_MAP_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BillboardSponsorLabel(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription =
                                stringResource(R.string.billboard_mapPopupClose),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                BillboardBody(headline = headline, message = message)
                if (ctaLabel != null) {
                    TextButton(
                        onClick = onCallToAction,
                        modifier = Modifier.testTag(BILLBOARD_MAP_POPUP_CTA_TAG),
                    ) {
                        Text(text = ctaLabel)
                    }
                }
            }
        }
    }
}
