package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.profile.SupporterAvatarFrame
import com.kungsbackacarcommunity.app.profile.ClubBadge

/**
 * Already-resolved owner presentation, supplied by the authenticated shell.
 * [avatarUrl] is the actual image URL, never a Storage path. [showSupporterBadge]
 * is the existing effective badge visibility (eligibility AND saved preference),
 * not a new entitlement or role. This component does not resolve Storage paths
 * or read profile/membership state.
 * [onOpenProfile] opens the existing owner Profile route.
 */
data class AeroOwnerHeader(
    val avatarUrl: String?,
    val showSupporterBadge: Boolean,
    val onOpenProfile: () -> Unit,
    /** Canonical users/{uid} projection, supplied by the existing shell profile snapshot. */
    val clubBadge: ClubBadge? = null,
) {
    // Preserve existing positional and trailing-lambda call sites.
    constructor(avatarUrl: String?, showSupporterBadge: Boolean, onOpenProfile: () -> Unit) :
        this(avatarUrl, showSupporterBadge, onOpenProfile, null)
}

/**
 * Opt-in shared owner shortcut. Null preserves the original page geometry and
 * title behavior. Re-provide null on the owner's Profile to avoid a second avatar.
 */
val LocalAeroOwnerHeader = staticCompositionLocalOf<AeroOwnerHeader?> { null }

const val AeroOwnerAvatarTag = "aeroOwnerAvatar"
internal const val AeroOwnerAvatarFallbackTag = "aeroOwnerAvatarFallback"

/** One 48dp row at normal text size; wraps vertically at large text/narrow widths. */
@Composable
internal fun AeroPageHeader(
    title: String,
    owner: AeroOwnerHeader,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    back: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        back?.invoke()
        Text(
            text = title,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .semantics { heading() }
                .then(
                    if (onTitleClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onTitleClick)
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = KccSpacing.s2),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AeroOwnerAvatar(owner)
    }
}

@Composable
private fun AeroOwnerAvatar(owner: AeroOwnerHeader) {
    val description = stringResource(R.string.navigation_profile)
    // The frame stays outside the photo clip so the existing Supporter crown
    // remains intact. The whole frame is one named 48dp profile action.
    Box(
        modifier = Modifier.size(48.dp).testTag(AeroOwnerAvatarTag)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(role = Role.Button, onClick = owner.onOpenProfile),
    ) {
        SupporterAvatarFrame(visible = owner.showSupporterBadge, clubBadge = owner.clubBadge) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                // Always underlay the native icon: URL resolution, Coil loading
                // and image failures must never leave an empty profile shortcut.
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp).testTag(AeroOwnerAvatarFallbackTag),
                )
                if (!owner.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = owner.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
