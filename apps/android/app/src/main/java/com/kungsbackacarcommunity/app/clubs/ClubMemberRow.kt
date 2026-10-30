package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R

@Composable
internal fun ClubMemberRow(
    member: ClubMember,
    viewerUid: String,
    viewerRole: ClubRole,
    moderatorCount: Int,
    busy: Boolean,
    onOpenProfile: (String) -> Unit,
    onAction: (ClubMemberAction, ClubMember) -> Unit,
) {
    val actions = clubMemberActions(viewerUid, viewerRole, member)
    var expanded by remember(member.uid, member.role, viewerRole) { mutableStateOf(false) }
    val name = member.displayName.ifBlank { stringResource(R.string.clubs_member) }
    val menuLabel = stringResource(R.string.clubPage_memberActions, name)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 72.dp)
                .testTag("club-member-profile-${member.uid}")
                .combinedClickable(
                    enabled = !busy,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.clubPage_openProfile, name),
                    onClick = { onOpenProfile(member.uid) },
                    onLongClickLabel = if (actions.isNotEmpty()) menuLabel else null,
                    onLongClick = if (actions.isNotEmpty()) ({ expanded = true }) else null,
                ).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClubMemberAvatar(member)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(member.role.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actions.isNotEmpty()) Box {
            IconButton(
                onClick = { expanded = true }, enabled = !busy,
                modifier = Modifier.size(48.dp).testTag("club-member-overflow-${member.uid}"),
            ) { Icon(Icons.Default.MoreVert, contentDescription = menuLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            DropdownMenu(expanded = expanded && !busy, onDismissRequest = { expanded = false }) {
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label()) },
                        enabled = action.enabled(moderatorCount, busy),
                        modifier = Modifier.testTag("club-member-action-${action.name}-${member.uid}"),
                        onClick = { expanded = false; onAction(action, member) },
                        colors = MenuDefaults.itemColors(textColor = if (action == ClubMemberAction.RemoveMember) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
                    )
                }
                if (ClubMemberAction.PromoteModerator in actions && moderatorCount >= 3) {
                    Text(stringResource(R.string.clubs_moderatorLimit), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun ClubMemberAction.label(): String = stringResource(when (this) {
    ClubMemberAction.PromoteModerator -> R.string.clubs_makeModerator
    ClubMemberAction.DemoteModerator -> R.string.clubs_removeModerator
    ClubMemberAction.TransferOwnership -> R.string.clubs_transfer
    ClubMemberAction.RemoveMember -> R.string.clubs_remove
})

@Composable
private fun ClubMemberAvatar(member: ClubMember) {
    var failed by remember(member.avatarUrl) { mutableStateOf(false) }
    Surface(Modifier.size(48.dp).clip(CircleShape).testTag("club-member-avatar-${member.uid}"),
        color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        if (member.avatarUrl != null && !failed) AsyncImage(
            model = member.avatarUrl, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, onError = { failed = true },
        ) else Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}
