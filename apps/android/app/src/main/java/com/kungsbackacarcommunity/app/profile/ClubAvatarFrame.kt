package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R

/** Overlay only: consumes an existing projection, without membership queries or Storage URL lookup. */
@Composable
fun ClubAvatarFrame(badge: ClubBadge?, content: @Composable BoxScope.() -> Unit) {
    Box {
        content()
        if (badge != null) {
            val description = stringResource(R.string.clubBadge_accessibility, badge.clubName)
            Box(
                modifier = Modifier.align(Alignment.TopStart).size(20.dp)
                    .testTag("club_avatar_badge")
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .semantics(mergeDescendants = true) { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                // Underlay remains visible while loading and on failure; never an empty logo.
                Text(
                    badge.initials, Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1, overflow = TextOverflow.Clip,
                )
                badge.logoUrl?.let { url ->
                    AsyncImage(
                        model = url, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().testTag("club_avatar_logo"),
                    )
                }
            }
        }
    }
}
