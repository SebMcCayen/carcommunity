package com.kungsbackacarcommunity.app.dm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding

/**
 * The DM inbox: one row per conversation (avatar, other member's name,
 * last-message preview, unread badge), newest-first. Tapping a row opens the
 * [ChatScreen] for that member. Rendered on the shared [AeroPage] chrome.
 *
 * [showTitle] is false when the inbox is a SECTION of something that has already
 * named it — the chat hub, where the member arrived by tapping (or swiping to) a
 * tab labelled "Friends". A "Messages" header directly under that tab says
 * nothing the tab has not already said. It stays true for the standalone
 * full-screen route (a DM push with no conversation id), where the header is the
 * only thing naming the screen.
 */
@Composable
fun ConversationListScreen(
    state: DmConversationsState,
    onOpenConversation: (DmConversation) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    showTitle: Boolean = true,
    // True when a bottom-end FAB overlays this list ("start a new dialogue"): a
    // trailing spacer then lets the last row scroll clear of the FAB rather than
    // sit permanently behind it.
    reserveBottomActionSpace: Boolean = false,
) {
    // Durable list: a LazyColumn so only visible rows compose. The title is the
    // first `item {}`; conversation rows are keyed by conversationId so
    // recomposition/scroll state stays stable as the live inbox updates.
    AeroLazyPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = aeroLazyContentPadding(),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s4),
        ) {
            if (showTitle) {
                item(key = "title") {
                    AeroPageTitle(stringResource(R.string.dm_title))
                }
            }

            when (state) {
                DmConversationsState.Loading ->
                    item(key = "loading") { CircularProgressIndicator() }

                is DmConversationsState.Error ->
                    item(key = "error") {
                        Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                            Text(
                                text = stringResource(R.string.dm_loadError),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetry) {
                                Text(stringResource(R.string.dm_retry))
                            }
                        }
                    }

                is DmConversationsState.Loaded ->
                    if (state.conversations.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.dm_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(
                            state.conversations,
                            key = { it.conversationId },
                        ) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                onClick = { onOpenConversation(conversation) },
                            )
                        }
                    }
            }

            if (reserveBottomActionSpace) {
                item(key = "fabSpacer") { Spacer(Modifier.height(FAB_CLEARANCE)) }
            }
        }
    }
}

/**
 * Bottom clearance reserved below the list when the "start a new dialogue" FAB
 * overlays it — a standard 56dp FAB plus its margin — so the last conversation
 * row can scroll fully into view above the button.
 */
private val FAB_CLEARANCE = 80.dp

@Composable
private fun ConversationRow(
    conversation: DmConversation,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            MemberAvatar(avatarPath = conversation.otherUser.avatarPath)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.otherUser.displayName
                        ?: stringResource(R.string.dm_unknownMember),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                conversation.lastMessage?.let { preview ->
                    Text(
                        text = preview.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (conversation.unreadCount > 0) {
                UnreadBadge(count = conversation.unreadCount)
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    // Expose a localized "N unread messages" label to accessibility services
    // rather than the bare number; mirrors the MapHome chat bubble's badge.
    val description = stringResource(R.string.dm_unreadCount, count)
    Box(
        modifier =
            Modifier
                .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = KccSpacing.s2)
                .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun MemberAvatar(avatarPath: String?) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
