package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun clubError(error: Throwable): String = stringResource(when ((error as? ClubException)?.code) {
    "cooldown", "join-cooldown", "cooldown-active" -> R.string.clubs_cooldownError
    "moderator-limit", "moderator-limit-reached" -> R.string.clubs_moderatorLimit
    "owner-transfer-required", "owner-must-transfer" -> R.string.clubs_ownershipError
    "feature-disabled" -> R.string.clubs_unavailable
    "legacy-conflict" -> R.string.clubs_conflict
    "invalid-logo" -> R.string.clubLogo_prepareError
    "logo-permission-denied" -> R.string.clubLogo_permissionError
    "logo-upload-failed" -> R.string.clubLogo_uploadError
    "permission-denied", "not-member" -> R.string.clubs_permissionError
    else -> R.string.clubs_error
})

@Composable
internal fun ClubRole.label(): String = stringResource(when (this) {
    ClubRole.Owner -> R.string.clubs_owner
    ClubRole.Moderator -> R.string.clubs_moderator
    ClubRole.Member -> R.string.clubs_member
})

@Composable
internal fun ClubLogo(club: Club, modifier: Modifier = Modifier) {
    var failed by remember(club.logoUrl) { mutableStateOf(false) }
    Surface(modifier.size(56.dp).clip(CircleShape), color = MaterialTheme.colorScheme.secondaryContainer) {
        if (club.logoUrl != null && !failed) AsyncImage(model = club.logoUrl,
            contentDescription = stringResource(R.string.clubs_logoDescription, club.name),
            contentScale = ContentScale.Crop, onError = { failed = true })
        else Box(contentAlignment = Alignment.Center) { Text(club.name.take(2).uppercase(Locale.ROOT), style = MaterialTheme.typography.titleMedium) }
    }
}

/** Explicit reading colors avoid Material Card's gold onPrimaryContainer inheritance. */
@Composable
internal fun clubCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
internal fun clubLocale(): Locale = LocalLocale.current.platformLocale

@Composable
internal fun ClubCard(club: Club, onOpen: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = clubCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ClubLogo(club)
                Column(Modifier.weight(1f)) {
                    Text(club.name, style = MaterialTheme.typography.titleMedium)
                    Text(club.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.clubs_membersCount, club.memberCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.clubPage_intro), style = MaterialTheme.typography.titleSmall)
            Text(club.description, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(if (club.visibility == "closed") R.string.clubs_closed else R.string.clubs_open), style = MaterialTheme.typography.labelMedium)
            if (onOpen != null) TextButton(onClick = onOpen) { Text(stringResource(R.string.clubs_title)) }
            content()
        }
    }
}

internal val clubZone: ZoneId = ZoneId.of("Europe/Stockholm")
@Composable
internal fun clubDate(millis: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", clubLocale())
    .withZone(clubZone).format(Instant.ofEpochMilli(millis))
internal fun clubCooldownActive(state: ClubState, nowMillis: Long) =
    state.legacyConflict || (clubInstant(state.joinAllowedAt)?.let { it > nowMillis } == true)
