package com.kungsbackacarcommunity.app.badges

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.text.DateFormat
import java.util.Date

/**
 * Badges list (Phase 12 slice 14). Read-only; renders the awarded badges with
 * their localized names (falling back to the denormalized name) and dates.
 */
@Composable
fun BadgesScreen(
    state: BadgesState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AeroPage(title = stringResource(R.string.badges_screenTitle), modifier = modifier) {
            when (state) {
                BadgesState.Loading ->
                    Text(
                        text = stringResource(R.string.badges_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                BadgesState.Error ->
                    Text(
                        text = stringResource(R.string.badges_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                is BadgesState.Loaded ->
                    if (state.badges.isEmpty()) {
                        EmptyState()
                    } else {
                        state.badges.forEach { badge -> BadgeCard(badge) }
                    }
            }
    }
}

@Composable
private fun EmptyState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.badges_empty),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.badges_emptyHint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BadgeCard(badge: Badge) {
    val nameRes = badgeNameRes(badge.key)
    val name = if (nameRes != null) stringResource(nameRes) else (badge.fallbackName ?: badge.key)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            badge.awardedAtMillis?.let { millis ->
                Text(
                    text =
                        "${stringResource(R.string.badges_awardedOn)} " +
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
