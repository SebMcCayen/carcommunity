package com.kungsbackacarcommunity.app.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl

/** Option 2: compact identity row, gold accent and two direct actions. */
@Composable
internal fun ConvoyInvitationCard(
    invitation: AppNotification,
    secondsRemaining: Int,
    busy: Boolean,
    error: String?,
    onJoin: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val avatar = rememberStorageImageUrl(LocalContext.current, invitation.inviterAvatarPath)
    Surface(
        modifier.testTag("convoy_invitation_card"),
        shape = RoundedCornerShape(16.dp), color = colors.surface, shadowElevation = 4.dp,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(colors.primary))
            Column(Modifier.weight(1f).padding(KccSpacing.s4), verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                Row(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = colors.onSurfaceVariant)
                        if (avatar != null) AsyncImage(avatar, contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                        val name = invitation.inviterDisplayName?.takeIf { it.isNotBlank() }
                        Text(name ?: stringResource(R.string.notifications_categoryConvoyInvite),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (name != null) stringResource(R.string.convoy_invitationFrom)
                            else invitation.previewText ?: invitation.title,
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = colors.primary)
                }
                if (error != null) Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                    OutlinedButton(onDecline, shape = RoundedCornerShape(12.dp), enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("convoy_invitation_decline")) {
                        Text(stringResource(R.string.convoy_invitationDecline))
                    }
                    Button(onJoin, shape = RoundedCornerShape(12.dp), enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("convoy_invitation_join")) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.convoy_invitationJoin))
                    }
                }
                // Wrap independently at large font sizes; no per-second live region.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                    Text(stringResource(R.string.convoy_invitationInboxHint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text(stringResource(R.string.convoy_invitationClosesIn, secondsRemaining), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
