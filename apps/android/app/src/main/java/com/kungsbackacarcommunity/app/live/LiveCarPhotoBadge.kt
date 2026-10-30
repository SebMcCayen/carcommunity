package com.kungsbackacarcommunity.app.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl

const val LIVE_MEMBERSHIP_RING_TAG = "live_membership_ring"
const val LIVE_CLUB_BADGE_TAG = "live_club_badge"

/** No profile reads here. Keeps the route rim outside the optional cosmetic ring. */
@Composable
fun LiveCarPhotoBadge(
    imagePath: String?,
    appearance: LiveMarkerAppearance = LiveMarkerAppearance(),
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val url = rememberStorageImageUrl(LocalContext.current, imagePath)
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(ringColor).padding(2.dp)
                .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) {
                AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Icon(painterResource(R.drawable.ic_generic_car), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(.68f))
            }
            if (appearance.showMembershipMapRing) {
                val label = stringResource(R.string.membershipMapRing_accessibility)
                Box(Modifier.fillMaxSize().border(2.dp, KccPalette.crownGold, CircleShape)
                    .testTag(LIVE_MEMBERSHIP_RING_TAG).semantics { contentDescription = label })
            }
        }
        appearance.clubBadge?.let { badge ->
            val label = stringResource(R.string.clubBadge_accessibility, badge.clubName)
            // Bottom centre leaves the top corners free for leader/count identifiers.
            // The 2dp extension fits inside both overlays' existing chip footprint.
            Box(
                Modifier.align(Alignment.BottomCenter).offset(y = 2.dp).size(16.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .testTag(LIVE_CLUB_BADGE_TAG).semantics(mergeDescendants = true) { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                // Initials stand in for a fixed-size logo image; the full club
                // name remains available in the accessible label at every font size.
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                    Text(badge.initials, Modifier.clearAndSetSemantics {},
                        style = MaterialTheme.typography.labelSmall, fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1, overflow = TextOverflow.Clip)
                }
                badge.logoUrl?.let { logo ->
                    AsyncImage(logo, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
