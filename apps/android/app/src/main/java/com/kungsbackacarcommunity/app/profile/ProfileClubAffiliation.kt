package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/** Public affiliation only: no navigation, membership queries, or role decisions. */
@Composable
internal fun ProfileClubAffiliation(badge: ClubBadge?) {
    if (badge == null) return

    Row(
        modifier = Modifier.fillMaxWidth().testTag("profile_club_affiliation")
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Announce the adjacent localized label once; the image is decorative here.
        Box(Modifier.clearAndSetSemantics {}) {
            ClubAvatarFrame(badge) { Box(Modifier.size(20.dp)) }
        }
        Text(
            text = stringResource(R.string.clubBadge_accessibility, badge.clubName),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
