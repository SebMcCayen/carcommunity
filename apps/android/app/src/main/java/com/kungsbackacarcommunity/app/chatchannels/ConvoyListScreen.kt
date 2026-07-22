package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chattime.ChatDateContext
import com.kungsbackacarcommunity.app.chattime.rememberChatDateContext
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * The Convoys tab list: one row per chat-eligible convoy (accepted member),
 * grouped into an ONGOING section (the caller's live convoy — at most one, per
 * the one-at-a-time rule) and a HISTORY section (ended convoys, still readable as
 * chat history). Each row is titled with its accepted members' names and the time
 * the convoy was created, so these otherwise-unnamed convoys are tellable apart at
 * a glance. Tapping a row opens its channel. Loads once via the suspend
 * `convoy-list` callable (convoys change rarely), with a retry on failure and an
 * empty state when the caller is in no convoys.
 */
@Composable
fun ConvoyListRoute(
    repository: ConvoyChatRepository,
    onOpenConvoy: (ChatConvoy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reloadKey by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<ConvoyListState>(ConvoyListState.Loading) }
    LaunchedEffect(reloadKey) {
        state = ConvoyListState.Loading
        state = repository.listConvoys()
    }

    ConvoyListScreen(
        state = state,
        onOpenConvoy = onOpenConvoy,
        onRetry = { reloadKey++ },
        modifier = modifier,
    )
}

@Composable
fun ConvoyListScreen(
    state: ConvoyListState,
    onOpenConvoy: (ChatConvoy) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(KccSpacing.s4)) {
        when (state) {
            ConvoyListState.Loading ->
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            ConvoyListState.Error ->
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                ) {
                    Text(
                        text = stringResource(R.string.chatHub_convoysError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.chatHub_convoysRetry))
                    }
                }

            is ConvoyListState.Loaded ->
                if (state.convoys.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chatHub_convoysEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    ConvoyList(convoys = state.convoys, onOpenConvoy = onOpenConvoy)
                }
        }
    }
}

@Composable
private fun ConvoyList(convoys: List<ChatConvoy>, onOpenConvoy: (ChatConvoy) -> Unit) {
    val dates = rememberChatDateContext()
    val grouped = remember(convoys) { ConvoyRowFormat.group(convoys) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        if (grouped.ongoing.isNotEmpty()) {
            item(key = "header-ongoing") {
                SectionHeader(stringResource(R.string.chatHub_convoyOngoingHeader))
            }
            items(grouped.ongoing, key = { it.convoyId }) { convoy ->
                ConvoyRow(
                    convoy = convoy,
                    dates = dates,
                    ongoing = true,
                    onClick = { onOpenConvoy(convoy) },
                )
            }
        }
        if (grouped.past.isNotEmpty()) {
            item(key = "header-history") {
                SectionHeader(stringResource(R.string.chatHub_convoyHistoryHeader))
            }
            items(grouped.past, key = { it.convoyId }) { convoy ->
                ConvoyRow(
                    convoy = convoy,
                    dates = dates,
                    ongoing = false,
                    onClick = { onOpenConvoy(convoy) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConvoyRow(
    convoy: ChatConvoy,
    dates: ChatDateContext,
    ongoing: Boolean,
    onClick: () -> Unit,
) {
    // Ended rows are de-emphasised (they are history), so the single live convoy
    // reads as the current one even before the reader parses the section headers.
    val rowAlpha = if (ongoing) 1f else HISTORY_ALPHA
    Card(
        modifier = Modifier.fillMaxWidth().alpha(rowAlpha).clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = convoyTitle(convoy),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(convoy.status)
            }
            convoy.createdAtMillis?.let { millis ->
                val created = ConvoyRowFormat.createdAtLabel(
                    millis = millis,
                    zone = dates.zone,
                    locale = dates.locale,
                    use24Hour = dates.use24Hour,
                    datePattern = stringResource(R.string.chatHub_convoyDatePattern),
                )
                Text(
                    text = stringResource(R.string.chatHub_convoyCreated, created),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The row title: the accepted members' names ("Alice, Bob +2"), falling back to a
 * plain member count, then the convoy's own title, then a generic label — so a
 * row is never blank however sparse its payload.
 */
@Composable
private fun convoyTitle(convoy: ChatConvoy): String {
    val label = ConvoyRowFormat.memberLabel(convoy.memberNames)
    return when {
        label.shownNames.isNotEmpty() -> {
            val names = label.shownNames.joinToString(", ")
            if (label.overflow > 0) {
                "$names ${stringResource(R.string.chatHub_convoyMemberOverflow, label.overflow)}"
            } else {
                names
            }
        }
        convoy.memberCount > 0 -> stringResource(R.string.chatHub_convoyMembers, convoy.memberCount)
        convoy.title != null -> convoy.title
        else -> stringResource(R.string.chatHub_convoyUntitled)
    }
}

@Composable
private fun StatusBadge(status: String) {
    data class BadgeColors(val text: String, val container: Color, val content: Color)
    val badge = when (status) {
        "active" -> BadgeColors(
            stringResource(R.string.chatHub_convoyActiveBadge),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
        )
        "ended" -> BadgeColors(
            stringResource(R.string.chatHub_convoyEndedBadge),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> BadgeColors(
            stringResource(R.string.chatHub_convoyFormingBadge),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Surface(
        color = badge.container,
        contentColor = badge.content,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = badge.text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s1),
        )
    }
}

private const val HISTORY_ALPHA = 0.6f
